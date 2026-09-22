package com.wikiagent.infrastructure.memory.milvus;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.wikiagent.config.WikiAgentProperties;
import com.wikiagent.domain.memory.HistoricalEvent;
import com.wikiagent.domain.memory.HistoricalEventRepository;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * v1-v2 §6 历史事件库端口 - Milvus 实现适配器。
 * <p>
 * 独立 collection {@code wikiagent_history}，与知识库 chunks collection 物理隔离。
 * Schema 字段：event_id (PK) + user_id + session_id + parent_id + event_type
 * + content + dense (向量) + metadata_json + created_at。
 * <p>
 * 父子索引：parent_id 为空字符串表示父事件；子事件 parent_id 引用父事件 event_id。
 * <p>
 * 连接失败不阻断应用启动；insert/search 失败仅告警并返回空列表，
 * 由上层调用方自行处理历史事件不可用场景。
 * <p>
 * search 的 query 参数约定为 JSON 字符串（float 数组），由调用方负责嵌入；
 * 若无法解析为向量，则告警并返回空列表。
 */
@Service
@ConditionalOnProperty(name = "wikiagent.memory.historical-adapter", havingValue = "milvus", matchIfMissing = true)
public class MilvusHistoricalEventRepository implements HistoricalEventRepository {

    private static final Logger log = LoggerFactory.getLogger(MilvusHistoricalEventRepository.class);

    /** 历史事件 collection 名称，与知识库 chunks 隔离。 */
    private static final String COLLECTION = "wikiagent_history";

    private static final String FIELD_EVENT_ID = "event_id";
    private static final String FIELD_USER_ID = "user_id";
    private static final String FIELD_SESSION_ID = "session_id";
    private static final String FIELD_PARENT_ID = "parent_id";
    private static final String FIELD_EVENT_TYPE = "event_type";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_DENSE = "dense";
    private static final String FIELD_METADATA = "metadata_json";
    private static final String FIELD_CREATED_AT = "created_at";

    private final WikiAgentProperties props;
    private final ObjectMapper mapper;
    private final Gson gson = new Gson();
    private final AtomicReference<MilvusClientV2> clientRef = new AtomicReference<>();
    private volatile boolean ensureTried = false;

    public MilvusHistoricalEventRepository(WikiAgentProperties props) {
        this.props = props;
        this.mapper = new ObjectMapper();
    }

    @Override
    public void insert(HistoricalEvent event) {
        if (event == null || event.eventId() == null) {
            return;
        }
        try {
            MilvusClientV2 c = client();
            if (c == null) {
                return;
            }
            ensureCollection(c);
            float[] vector = parseEmbedding(event.embedding());
            if (vector == null) {
                log.warn("历史事件插入跳过：embedding 缺失或无效 eventId={}", event.eventId());
                return;
            }
            JsonObject row = new JsonObject();
            row.addProperty(FIELD_EVENT_ID, event.eventId());
            row.addProperty(FIELD_USER_ID, nullSafe(event.userId()));
            row.addProperty(FIELD_SESSION_ID, nullSafe(event.sessionId()));
            row.addProperty(FIELD_PARENT_ID, event.parentId() == null ? "" : event.parentId());
            row.addProperty(FIELD_EVENT_TYPE, nullSafe(event.eventType()));
            row.addProperty(FIELD_CONTENT, nullSafe(event.content()));
            row.add(FIELD_DENSE, gson.toJsonTree(vector));
            row.addProperty(FIELD_METADATA, metadataToJson(event.metadata()));
            row.addProperty(FIELD_CREATED_AT, event.createdAt() == null
                    ? Instant.now().toString() : event.createdAt().toString());
            c.insert(InsertReq.builder()
                    .collectionName(COLLECTION)
                    .data(List.of(row))
                    .build());
        } catch (Exception e) {
            log.warn("历史事件插入失败 eventId={}: {}", event.eventId(), e.getMessage());
        }
    }

    @Override
    public List<HistoricalEvent> search(String query, int topK) {
        float[] vector = parseEmbedding(query);
        if (vector == null) {
            log.warn("历史事件检索跳过：query 不是有效的向量 JSON");
            return List.of();
        }
        return searchByVector(vector, Math.max(1, topK), null);
    }

    @Override
    public List<HistoricalEvent> findByUserId(String userId, int limit) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        // 用一个零向量召回后通过 filter 过滤；简化版：直接通过 query filter 检索
        // Milvus 检索必须传向量；这里用 filter 限定 userId 后取 topK=limit
        int dim = Math.max(1, props.milvus().dimension());
        float[] zeroVec = new float[dim];
        // 用 query filter 限定 user_id（zeroVec 召回顺序不保证，仅用于满足接口要求）
        return searchByVector(zeroVec, Math.max(1, limit),
                FIELD_USER_ID + " == \"" + userId.replace("\"", "") + "\"");
    }

    /** 真正的向量检索 + 可选 filter。Milvus 不可用或失败返回空列表。 */
    private List<HistoricalEvent> searchByVector(float[] vector, int topK, String filter) {
        try {
            MilvusClientV2 c = client();
            if (c == null) {
                return List.of();
            }
            ensureCollection(c);
            SearchReq.SearchReqBuilder<?, ?> b = SearchReq.builder()
                    .collectionName(COLLECTION)
                    .data(List.of(new FloatVec(vector)))
                    .annsField(FIELD_DENSE)
                    .topK(topK)
                    .outputFields(List.of(FIELD_EVENT_ID, FIELD_USER_ID, FIELD_SESSION_ID,
                            FIELD_PARENT_ID, FIELD_EVENT_TYPE, FIELD_CONTENT,
                            FIELD_METADATA, FIELD_CREATED_AT));
            if (filter != null && !filter.isBlank()) {
                b.filter(filter);
            }
            SearchResp resp = c.search(b.build());
            List<List<SearchResp.SearchResult>> results = resp.getSearchResults();
            if (results == null || results.isEmpty()) {
                return List.of();
            }
            List<HistoricalEvent> out = new ArrayList<>(results.get(0).size());
            for (SearchResp.SearchResult hit : results.get(0)) {
                Map<String, Object> e = hit.getEntity();
                out.add(new HistoricalEvent(
                        str(e.get(FIELD_EVENT_ID)),
                        str(e.get(FIELD_USER_ID)),
                        str(e.get(FIELD_SESSION_ID)),
                        str(e.get(FIELD_PARENT_ID)),
                        str(e.get(FIELD_EVENT_TYPE)),
                        str(e.get(FIELD_CONTENT)),
                        null, // embedding 不回传（避免大字段传输）
                        parseMetadata(str(e.get(FIELD_METADATA))),
                        parseInstant(str(e.get(FIELD_CREATED_AT)))
                ));
            }
            return out;
        } catch (Exception e) {
            log.warn("历史事件检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 懒加载 Milvus 客户端，失败返回 null（不抛异常）。 */
    private MilvusClientV2 client() {
        MilvusClientV2 c = clientRef.get();
        if (c != null) {
            return c;
        }
        synchronized (this) {
            c = clientRef.get();
            if (c != null) {
                return c;
            }
            try {
                WikiAgentProperties.Milvus mc = props.milvus();
                ConnectConfig cfg = ConnectConfig.builder()
                        .uri(mc.uri())
                        .username(mc.username())
                        .password(mc.password())
                        .dbName(mc.database() == null ? "default" : mc.database())
                        .build();
                c = new MilvusClientV2(cfg);
                c.getServerVersion();
                clientRef.set(c);
                log.info("Milvus 历史事件库连接成功: {}", mc.uri());
                return c;
            } catch (Exception e) {
                log.warn("Milvus 历史事件库连接失败: {}", e.getMessage());
                return null;
            }
        }
    }

    /** 第一次使用时创建 collection（幂等）；失败仅告警，不阻断后续操作。 */
    private synchronized void ensureCollection(MilvusClientV2 c) {
        if (ensureTried) {
            return;
        }
        try {
            Boolean exists = c.hasCollection(HasCollectionReq.builder()
                    .collectionName(COLLECTION).build());
            if (Boolean.TRUE.equals(exists)) {
                ensureTried = true;
                return;
            }
            int dim = Math.max(1, props.milvus().dimension());
            CreateCollectionReq.CollectionSchema schema = c.createSchema();
            schema.addField(AddFieldReq.builder()
                    .fieldName(FIELD_EVENT_ID).dataType(DataType.VarChar)
                    .maxLength(64).isPrimaryKey(true).build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(FIELD_USER_ID).dataType(DataType.VarChar).maxLength(64).build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(FIELD_SESSION_ID).dataType(DataType.VarChar).maxLength(64).build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(FIELD_PARENT_ID).dataType(DataType.VarChar).maxLength(64).build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(FIELD_EVENT_TYPE).dataType(DataType.VarChar).maxLength(32).build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(FIELD_CONTENT).dataType(DataType.VarChar).maxLength(65535).build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(FIELD_DENSE).dataType(DataType.FloatVector).dimension(dim).build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(FIELD_METADATA).dataType(DataType.VarChar).maxLength(8192).build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(FIELD_CREATED_AT).dataType(DataType.VarChar).maxLength(32).build());

            List<IndexParam> indexes = List.of(IndexParam.builder()
                    .fieldName(FIELD_DENSE)
                    .indexType(IndexParam.IndexType.HNSW)
                    .metricType(IndexParam.MetricType.COSINE)
                    .extraParams(Map.of("M", 16, "efConstruction", 200))
                    .build());

            c.createCollection(CreateCollectionReq.builder()
                    .collectionName(COLLECTION)
                    .collectionSchema(schema)
                    .indexParams(indexes)
                    .build());
            c.loadCollection(LoadCollectionReq.builder()
                    .collectionName(COLLECTION).build());
            log.info("Milvus 历史事件 collection 已创建: {} (dim={})", COLLECTION, dim);
        } catch (Exception e) {
            log.warn("Milvus 历史事件 collection 创建/校验失败: {}", e.getMessage());
        } finally {
            ensureTried = true;
        }
    }

    /** 解析 embedding JSON 字符串 → float[]；失败返回 null。 */
    private float[] parseEmbedding(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            List<Float> list = mapper.readValue(json, new TypeReference<List<Float>>() {
            });
            if (list == null || list.isEmpty()) {
                return null;
            }
            float[] arr = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                arr[i] = list.get(i);
            }
            return arr;
        } catch (Exception e) {
            return null;
        }
    }

    private String metadataToJson(List<String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "[]";
        }
        try {
            return mapper.writeValueAsString(metadata);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> list = mapper.readValue(json, new TypeReference<List<String>>() {
            });
            return list == null ? List.of() : list;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /** 删除指定用户的历史事件（管理接口用，不属接口契约但便于清理）。 */
    public void deleteByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        try {
            MilvusClientV2 c = client();
            if (c == null) {
                return;
            }
            ensureCollection(c);
            c.delete(DeleteReq.builder()
                    .collectionName(COLLECTION)
                    .filter(FIELD_USER_ID + " == \"" + userId.replace("\"", "") + "\"")
                    .build());
        } catch (Exception e) {
            log.warn("历史事件删除失败 userId={}: {}", userId, e.getMessage());
        }
    }
}
