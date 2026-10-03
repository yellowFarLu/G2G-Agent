package com.wikiagent.application.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.domain.graph.GraphEdge;
import com.wikiagent.domain.graph.GraphExtractionResult;
import com.wikiagent.domain.graph.GraphNode;
import com.wikiagent.entity.KbChildChunk;
import com.wikiagent.entity.graph.GraphEntity;
import com.wikiagent.entity.graph.GraphRelation;
import com.wikiagent.repo.graph.GraphEntityRepo;
import com.wikiagent.repo.graph.GraphRelationRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GraphRAG LLM 抽取服务：从 chunk 文本中抽取实体和关系，写入 graph_entity / graph_relation。
 * 同名同类型实体冲突时 upsert 合并 description；source+target+type 关系冲突时累加 weight 合并 description。
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "wikiagent.graph.enabled", havingValue = "true", matchIfMissing = false)
public class GraphExtractionService {

    private static final Logger log = LoggerFactory.getLogger(GraphExtractionService.class);

    private static final String SYSTEM_PROMPT = """
            你是企业知识图谱构建助手。请从给定文本中抽取实体（entity）和关系（relation）。
            实体类型限定：人物/组织/产品/地点/概念/事件/规则/其他。
            关系类型限定：属于/包含/依赖/影响/合作/对抗/引用/继承/其他。
            输出为 JSON 数组，不要输出额外解释：
            {"entities": [{"name": "实体名", "type": "类型", "description": "一句话描述"}],
             "relations": [{"source": "源实体名", "target": "目标实体名", "type": "关系类型", "description": "一句话描述"}]}
            只抽取文本中明确提及的实体和关系，不要编造。文本中没有可抽取内容时返回 {"entities": [], "relations": []}。
            """;

    private final GraphEntityRepo entityRepo;
    private final GraphRelationRepo relationRepo;
    private final ChatModel extractionModel;
    private final int maxCharsPerChunk;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GraphExtractionService(GraphEntityRepo entityRepo,
                                  GraphRelationRepo relationRepo,
                                  @Qualifier("simpleChatModel") ChatModel extractionModel,
                                  @Value("${wikiagent.graph.max-chars-per-chunk:1500}") int maxCharsPerChunk) {
        this.entityRepo = entityRepo;
        this.relationRepo = relationRepo;
        this.extractionModel = extractionModel;
        this.maxCharsPerChunk = maxCharsPerChunk;
    }

    /**
     * 对单个 chunk 执行 LLM 抽取并写入图谱。失败时记录日志并返回空结果（不阻断入库）。
     */
    @Transactional
    public GraphExtractionResult extractFromChunk(KbChildChunk chunk) {
        if (chunk == null || chunk.getContent() == null || chunk.getContent().isBlank()) {
            return GraphExtractionResult.empty();
        }
        String text = chunk.getContent();
        if (text.length() > maxCharsPerChunk) {
            text = text.substring(0, maxCharsPerChunk);
        }
        try {
            String raw = extractionModel.call(new Prompt(
                    List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage("文本如下：\n" + text))
            )).getResult().getOutput().getText();
            return parseJson(raw, chunk.getDocId(), chunk.getId());
        } catch (Exception e) {
            log.warn("GraphRAG 抽取失败 chunkId={}: {}", chunk.getId(), e.getMessage());
            return GraphExtractionResult.empty();
        }
    }

    /**
     * 批量对文档全部有效 chunk 执行抽取（供 ingest 后异步调用）。
     */
    @Transactional
    public void extractFromDocument(String docId, List<KbChildChunk> chunks) {
        int totalEntities = 0;
        int totalRelations = 0;
        for (KbChildChunk chunk : chunks) {
            GraphExtractionResult r = extractFromChunk(chunk);
            totalEntities += r.entities().size();
            totalRelations += r.relations().size();
        }
        log.info("GraphRAG 抽取完成 docId={} chunks={} entities={} relations={}",
                docId, chunks.size(), totalEntities, totalRelations);
    }

    /** 解析 LLM JSON 输出，构建领域模型（带 docId/chunkId）。 */
    private GraphExtractionResult parseJson(String raw, String docId, String chunkId) {
        if (raw == null || raw.isBlank()) {
            return GraphExtractionResult.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(extractJson(raw));
            List<GraphNode> entities = new ArrayList<>();
            List<GraphEdge> relations = new ArrayList<>();
            Map<String, String> nameToId = new HashMap<>();

            JsonNode ents = root.path("entities");
            if (ents.isArray()) {
                for (JsonNode e : ents) {
                    String name = e.path("name").asText("").trim();
                    if (name.isEmpty()) continue;
                    String type = normalizeType(e.path("type").asText("其他"));
                    String desc = e.path("description").asText("").trim();
                    String id = upsertEntity(name, type, desc, docId, chunkId);
                    nameToId.put(name, id);
                    entities.add(new GraphNode(id, name, type, desc, docId, chunkId));
                }
            }

            JsonNode rels = root.path("relations");
            if (rels.isArray()) {
                for (JsonNode r : rels) {
                    String srcName = r.path("source").asText("").trim();
                    String tgtName = r.path("target").asText("").trim();
                    if (srcName.isEmpty() || tgtName.isEmpty()) continue;
                    String type = normalizeRelationType(r.path("type").asText("其他"));
                    String desc = r.path("description").asText("").trim();
                    String srcId = nameToId.get(srcName);
                    String tgtId = nameToId.get(tgtName);
                    if (srcId == null || tgtId == null) continue;
                    String id = upsertRelation(srcId, tgtId, type, desc, docId, chunkId);
                    relations.add(new GraphEdge(id, srcId, tgtId, type, desc, 1.0, docId, chunkId));
                }
            }
            return new GraphExtractionResult(entities, relations);
        } catch (Exception e) {
            log.warn("GraphRAG JSON 解析失败: {}", e.getMessage());
            return GraphExtractionResult.empty();
        }
    }

    /** 提取文本中第一个 JSON 对象（处理 LLM 可能输出 markdown 包裹的情况）。 */
    private String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return raw;
        }
        return raw.substring(start, end + 1);
    }

    /** 实体类型归一化。 */
    private String normalizeType(String raw) {
        if (raw == null) return "其他";
        return switch (raw.trim()) {
            case "人物", "组织", "产品", "地点", "概念", "事件", "规则" -> raw.trim();
            default -> "其他";
        };
    }

    /** 关系类型归一化。 */
    private String normalizeRelationType(String raw) {
        if (raw == null) return "其他";
        return switch (raw.trim()) {
            case "属于", "包含", "依赖", "影响", "合作", "对抗", "引用", "继承" -> raw.trim();
            default -> "其他";
        };
    }

    /** upsert 实体：name+type 冲突时合并 description 并更新 updatedAt。 */
    private String upsertEntity(String name, String type, String description, String docId, String chunkId) {
        var existing = entityRepo.findByNameAndType(name, type);
        if (existing.isPresent()) {
            GraphEntity e = existing.get();
            e.setDescription(mergeDescription(e.getDescription(), description));
            e.setUpdatedAt(LocalDateTime.now());
            entityRepo.save(e);
            return e.getId();
        }
        GraphEntity e = new GraphEntity();
        e.setId(UUID.randomUUID().toString());
        e.setName(name);
        e.setType(type);
        e.setDescription(description);
        e.setSourceDocId(docId);
        e.setSourceChunkId(chunkId);
        e.setActive(true);
        e.setCreatedAt(LocalDateTime.now());
        e.setUpdatedAt(LocalDateTime.now());
        entityRepo.save(e);
        return e.getId();
    }

    /** upsert 关系：source+target+type 冲突时累加 weight 并合并 description。 */
    private String upsertRelation(String srcId, String tgtId, String type, String description,
                                  String docId, String chunkId) {
        // 简化：按 docId + source + target + type 查询（H2/MySQL 兼容）
        var existing = relationRepo.findBySourceDocId(docId).stream()
                .filter(r -> r.getSourceEntityId().equals(srcId)
                        && r.getTargetEntityId().equals(tgtId)
                        && r.getRelationType().equals(type)
                        && r.isActive())
                .findFirst();
        if (existing.isPresent()) {
            GraphRelation r = existing.get();
            r.setWeight(r.getWeight() + 1.0);
            r.setDescription(mergeDescription(r.getDescription(), description));
            r.setUpdatedAt(LocalDateTime.now());
            relationRepo.save(r);
            return r.getId();
        }
        GraphRelation r = new GraphRelation();
        r.setId(UUID.randomUUID().toString());
        r.setSourceEntityId(srcId);
        r.setTargetEntityId(tgtId);
        r.setRelationType(type);
        r.setDescription(description);
        r.setWeight(1.0);
        r.setSourceDocId(docId);
        r.setSourceChunkId(chunkId);
        r.setActive(true);
        r.setCreatedAt(LocalDateTime.now());
        r.setUpdatedAt(LocalDateTime.now());
        relationRepo.save(r);
        return r.getId();
    }

    /** 合并描述：保留较长的描述，或拼接新信息。 */
    private String mergeDescription(String oldDesc, String newDesc) {
        if (oldDesc == null || oldDesc.isBlank()) return newDesc;
        if (newDesc == null || newDesc.isBlank()) return oldDesc;
        if (oldDesc.contains(newDesc) || newDesc.contains(oldDesc)) {
            return oldDesc.length() >= newDesc.length() ? oldDesc : newDesc;
        }
        // 简单拼接，避免无限增长：截断到 2000 字符
        String merged = oldDesc + "；" + newDesc;
        return merged.length() > 2000 ? merged.substring(0, 2000) : merged;
    }

    /** 删除文档时软下线关联实体和关系。 */
    @Transactional
    public void deactivateByDocId(String docId) {
        int e = entityRepo.deactivateByDocId(docId);
        int r = relationRepo.deactivateByDocId(docId);
        log.info("GraphRAG 文档下线 docId={} entities={} relations={}", docId, e, r);
    }
}
