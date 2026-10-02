package com.wikiagent.infrastructure.memory.mysql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wikiagent.domain.memory.HandoverRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Task 5 交接清单 MySQL 适配器（V9 四表，规格 2.5）。
 * <p>
 * 与 {@code FileHandoverRepository} 产出逐字同构的四段 JSON：
 * originalRequest / executedNodes / abandonedPaths / dataReferences。
 * <ul>
 *   <li>init 语义：insert-if-absent，不清空（original_request 不可变）；并发由 uk_user_session 兜底</li>
 *   <li>已执行节点写 handover_node（status=COMPLETED，result=描述），seq 单调递增</li>
 *   <li>数据引用写即 upsert（uk_checklist_key）</li>
 *   <li>refreshFieldIndex 不再调 Python：字段索引由 {@link DataRefValueResolver} 确定性解析</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "wikiagent.memory.handover-adapter", havingValue = "mysql", matchIfMissing = true)
public class MysqlHandoverRepository implements HandoverRepository {

    private static final Logger log = LoggerFactory.getLogger(MysqlHandoverRepository.class);

    private final HandoverChecklistJpaDao checklistDao;
    private final HandoverNodeJpaDao nodeDao;
    private final HandoverAbandonedPathJpaDao abandonedPathDao;
    private final HandoverDataRefJpaDao dataRefDao;
    private final DataRefValueResolver dataRefValueResolver;
    private final ObjectMapper mapper;

    public MysqlHandoverRepository(HandoverChecklistJpaDao checklistDao,
                                   HandoverNodeJpaDao nodeDao,
                                   HandoverAbandonedPathJpaDao abandonedPathDao,
                                   HandoverDataRefJpaDao dataRefDao) {
        this.checklistDao = checklistDao;
        this.nodeDao = nodeDao;
        this.abandonedPathDao = abandonedPathDao;
        this.dataRefDao = dataRefDao;
        this.dataRefValueResolver = new DataRefValueResolver();
        this.mapper = new ObjectMapper();
    }

    @Override
    @Transactional
    public void init(String userId, String sessionId, String originalRequest) {
        ensureChecklist(userId, sessionId,
                originalRequest == null ? "" : originalRequest);
    }

    @Override
    @Transactional
    public void addExecutedNode(String userId, String sessionId, String nodeId, String description) {
        HandoverChecklistEntity checklist = ensureChecklist(userId, sessionId, "");
        List<HandoverNodeEntity> existing = nodeDao.findByChecklistIdOrderBySeqAsc(checklist.getId());
        int nextSeq = existing.isEmpty() ? 1
                : (existing.get(existing.size() - 1).getSeq() == null ? 0 : existing.get(existing.size() - 1).getSeq()) + 1;
        HandoverNodeEntity node = new HandoverNodeEntity();
        node.setChecklistId(checklist.getId());
        node.setNodeId(nodeId == null ? "" : nodeId);
        node.setResult(description == null ? "" : description);
        node.setStatus("COMPLETED");
        node.setSeq(nextSeq);
        node.setCreatedAt(LocalDateTime.now());
        nodeDao.save(node);
    }

    @Override
    @Transactional
    public void addAbandonedPath(String userId, String sessionId, String nodeId, String reason) {
        HandoverChecklistEntity checklist = ensureChecklist(userId, sessionId, "");
        HandoverAbandonedPathEntity path = new HandoverAbandonedPathEntity();
        path.setChecklistId(checklist.getId());
        path.setNodeId(nodeId == null ? "" : nodeId);
        path.setReason(reason == null ? "" : reason);
        path.setCreatedAt(LocalDateTime.now());
        abandonedPathDao.save(path);
    }

    @Override
    @Transactional
    public void addDataReference(String userId, String sessionId, String key, String value) {
        HandoverChecklistEntity checklist = ensureChecklist(userId, sessionId, "");
        String refKey = key == null ? "" : key;
        HandoverDataRefEntity ref = dataRefDao.findByChecklistIdAndRefKey(checklist.getId(), refKey)
                .orElseGet(HandoverDataRefEntity::new);
        ref.setChecklistId(checklist.getId());
        ref.setRefKey(refKey);
        ref.setRefValue(value == null ? "" : value);
        ref.setUpdatedAt(LocalDateTime.now());
        dataRefDao.save(ref);
    }

    @Override
    public String load(String userId, String sessionId) {
        HandoverChecklistEntity checklist = checklistDao.findByUserIdAndSessionId(userId, sessionId).orElse(null);
        if (checklist == null) {
            return null;
        }
        ObjectNode root = mapper.createObjectNode();
        root.put("originalRequest", checklist.getOriginalRequest());

        ArrayNode executed = root.putArray("executedNodes");
        for (HandoverNodeEntity node : nodeDao.findByChecklistIdOrderBySeqAsc(checklist.getId())) {
            ObjectNode item = executed.addObject();
            item.put("nodeId", node.getNodeId());
            item.put("description", node.getResult() == null ? "" : node.getResult());
        }

        ArrayNode abandoned = root.putArray("abandonedPaths");
        for (HandoverAbandonedPathEntity path : abandonedPathDao.findByChecklistId(checklist.getId())) {
            ObjectNode item = abandoned.addObject();
            item.put("nodeId", path.getNodeId());
            item.put("reason", path.getReason() == null ? "" : path.getReason());
        }

        ArrayNode refs = root.putArray("dataReferences");
        for (HandoverDataRefEntity ref : dataRefDao.findByChecklistId(checklist.getId())) {
            ObjectNode item = refs.addObject();
            item.put("key", ref.getRefKey());
            item.put("value", ref.getRefValue() == null ? "" : ref.getRefValue());
        }
        return root.toString();
    }

    @Override
    public void refreshFieldIndex(String userId, String sessionId) {
        // 不再调用 Python 脚本：字段索引即 dataReferences 表内容，确定性解析即时可得
        String json = load(userId, sessionId);
        if (json == null) {
            return;
        }
        Map<String, String> index = dataRefValueResolver.resolve(json);
        log.debug("交接清单字段索引已解析 userId={} sessionId={} keys={}", userId, sessionId, index.keySet());
    }

    /** insert-if-absent：存在即原样返回（original_request 不可变），缺失则插入。 */
    private HandoverChecklistEntity ensureChecklist(String userId, String sessionId, String originalRequest) {
        HandoverChecklistEntity existing = checklistDao.findByUserIdAndSessionId(userId, sessionId).orElse(null);
        if (existing != null) {
            return existing;
        }
        HandoverChecklistEntity checklist = new HandoverChecklistEntity();
        checklist.setUserId(userId);
        checklist.setSessionId(sessionId);
        checklist.setOriginalRequest(originalRequest);
        checklist.setStatus("ACTIVE");
        checklist.setVersion(0);
        LocalDateTime now = LocalDateTime.now();
        checklist.setCreatedAt(now);
        checklist.setUpdatedAt(now);
        try {
            return checklistDao.save(checklist);
        } catch (DataIntegrityViolationException e) {
            // uk_user_session 并发兜底：已存在则取既有行
            return checklistDao.findByUserIdAndSessionId(userId, sessionId).orElseThrow(() -> e);
        }
    }
}
