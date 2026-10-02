package com.wikiagent.application.rule;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.application.lineage.ProvenanceService;
import com.wikiagent.domain.extract.ExtractedFieldValue;
import com.wikiagent.domain.extract.FieldSource;
import com.wikiagent.domain.extract.FieldValueType;
import com.wikiagent.domain.lineage.EdgeType;
import com.wikiagent.domain.rule.RuleComputation;
import com.wikiagent.domain.rule.RuleSet;
import com.wikiagent.domain.rule.RuleStatus;
import com.wikiagent.domain.rule.dsl.DeterministicRuleEngine;
import com.wikiagent.domain.rule.dsl.RuleEngineException;
import com.wikiagent.domain.rule.dsl.RuleOutcome;
import com.wikiagent.domain.rule.dsl.RuleProgram;
import com.wikiagent.dto.ConflictException;
import com.wikiagent.dto.NotFoundException;
import com.wikiagent.entity.rule.RuleComputationEntity;
import com.wikiagent.repo.rule.RuleComputationRepo;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 规则执行服务：确定性重算、字段落库、跨版本回放。
 */
@Service
public class RuleExecutionService {

    private final RuleSetService ruleSetService;
    private final RuleComputationRepo computationRepo;
    private final RuleDslParser parser;
    private final ProvenanceService provenance;
    private final ReviewCaseService reviewCaseService;
    private final ObjectMapper mapper;
    private final DeterministicRuleEngine engine = new DeterministicRuleEngine();

    public RuleExecutionService(RuleSetService ruleSetService,
                                RuleComputationRepo computationRepo,
                                ProvenanceService provenance,
                                ReviewCaseService reviewCaseService,
                                ObjectMapper mapper) {
        this.ruleSetService = ruleSetService;
        this.computationRepo = computationRepo;
        this.provenance = provenance;
        this.reviewCaseService = reviewCaseService;
        this.mapper = mapper;
        this.parser = new RuleDslParser(mapper);
    }

    /**
     * 执行规则：仅 ACTIVE 版本可执行新计算；FAILED 也落库。
     */
    @Transactional
    public RuleComputation execute(String code, String docId, Map<String, Object> input) {
        RuleSet active = ruleSetService.listByCode(code).stream()
                .filter(r -> r.status() == RuleStatus.ACTIVE)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("规则无 ACTIVE 版本: " + code));
        return doExecute(active, docId, input);
    }

    /**
     * 执行并应用：成功后将标量输出落 extracted_field + RULED 血缘边。
     */
    @Transactional
    public RuleComputation executeAndApply(String docId, String code, Map<String, Object> input) {
        RuleComputation comp = execute(code, docId, input);
        if (comp.status() != RuleComputation.ComputationStatus.SUCCESS) {
            return comp;
        }
        applyOutputs(docId, code, comp.ruleVersion(), parseJsonMap(comp.outputJson()));
        checkMaterialDiff(docId, code, comp);
        return comp;
    }

    /**
     * 回放：取原 computation 输入，用目标版本（任意状态）重跑，落新 computation，返回输出字段级 diff。
     */
    @Transactional
    public Map<String, Object> replay(Long computationId, int targetVersion) {
        RuleComputationEntity origin = computationRepo.findById(computationId)
                .orElseThrow(() -> new NotFoundException("计算记录不存在: " + computationId));
        RuleSet target = ruleSetService.get(origin.getRuleCode(), targetVersion);
        Map<String, Object> input = parseJsonMap(origin.getInputSnapshot());
        RuleComputation replayed = doExecute(target, origin.getDocId(), input);

        Map<String, Object> oldOut = parseJsonMap(origin.getOutputJson());
        Map<String, Object> newOut = parseJsonMap(replayed.outputJson());
        List<Map<String, Object>> diff = diffOutputs(oldOut, newOut);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("replayId", replayed.id());
        result.put("originId", computationId);
        result.put("targetVersion", targetVersion);
        result.put("diff", diff);
        return result;
    }

    public RuleComputation getComputation(Long id) {
        return computationRepo.findById(id).map(this::toDomain)
                .orElseThrow(() -> new NotFoundException("计算记录不存在: " + id));
    }

    // ===== 内部 =====

    private RuleComputation doExecute(RuleSet rule, String docId, Map<String, Object> input) {
        long start = System.nanoTime();
        String traceId = MDC.get("traceId");
        String inputSnapshot = writeJson(input == null ? Map.of() : input);
        RuleComputationEntity e = new RuleComputationEntity();
        e.setRuleCode(rule.code());
        e.setRuleVersion(rule.version());
        e.setDocId(docId);
        e.setInputSnapshot(inputSnapshot);
        e.setTraceId(traceId);
        e.setComputedAt(Instant.now());
        try {
            RuleProgram program = parser.parse(rule.dslJson());
            RuleOutcome outcome = engine.run(program, input);
            e.setStatus(RuleComputation.ComputationStatus.SUCCESS.name());
            e.setIntermediatesJson(writeJson(outcome.intermediates()));
            e.setOutputJson(writeJson(outcome.outputs()));
        } catch (RuleEngineException ex) {
            e.setStatus(RuleComputation.ComputationStatus.FAILED.name());
            e.setError(truncate(ex.getMessage(), 512));
        } catch (Exception ex) {
            e.setStatus(RuleComputation.ComputationStatus.FAILED.name());
            e.setError(truncate(ex.getClass().getSimpleName() + ": " + ex.getMessage(), 512));
        }
        e.setDurationMs((System.nanoTime() - start) / 1_000_000);
        return toDomain(computationRepo.save(e));
    }

    private void applyOutputs(String docId, String code, int version, Map<String, Object> outputs) {
        int docVersionNo = provenance.latestVersion(docId) != null
                ? provenance.latestVersion(docId).versionNo() : 1;
        String schemaKey = "rule:" + code;
        String schemaVersion = String.valueOf(version);
        for (Map.Entry<String, Object> entry : outputs.entrySet()) {
            Object v = entry.getValue();
            if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean) {
                FieldValueType type = v instanceof Number ? FieldValueType.NUMBER
                        : v instanceof Boolean ? FieldValueType.BOOLEAN : FieldValueType.STRING;
                ExtractedFieldValue value = new ExtractedFieldValue(entry.getKey(),
                        v == null ? null : String.valueOf(v), type, 1.0, FieldSource.RULE,
                        true, List.of(), List.of());
                provenance.upsertField(docId, docVersionNo, value, schemaKey, schemaVersion,
                        entry.getKey(), false);
                provenance.addEdge(docId, docVersionNo, code + "@" + version, "RULE_SET",
                        entry.getKey(), "FIELD", EdgeType.RULED, "规则计算产出");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void checkMaterialDiff(String docId, String code, RuleComputation comp) {
        if (comp.outputJson() == null) {
            return;
        }
        try {
            Map<String, Object> outputs = parseJsonMap(comp.outputJson());
            for (Map.Entry<String, Object> entry : outputs.entrySet()) {
                if (entry.getValue() instanceof List<?> list) {
                    List<Map<String, Object>> rows = (List<Map<String, Object>>) list;
                    boolean hasMismatch = rows.stream()
                            .anyMatch(r -> Boolean.FALSE.equals(r.get("match")));
                    if (hasMismatch) {
                        reviewCaseService.createMaterialDiff(docId, code, comp.ruleVersion(),
                                comp.id(), writeJson(rows));
                    }
                }
            }
        } catch (Exception ignored) {
            // materialDiff 检测失败不影响主流程
        }
    }

    private List<Map<String, Object>> diffOutputs(Map<String, Object> oldOut,
                                                  Map<String, Object> newOut) {
        return java.util.stream.Stream.concat(oldOut.keySet().stream(), newOut.keySet().stream())
                .distinct().sorted()
                .map(key -> {
                    Object ov = oldOut.get(key);
                    Object nv = newOut.get(key);
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    row.put("key", key);
                    row.put("old", ov);
                    row.put("new", nv);
                    row.put("changed", !Objects.equals(ov, nv));
                    return row;
                }).toList();
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String writeJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private RuleComputation toDomain(RuleComputationEntity e) {
        return new RuleComputation(e.getId(), e.getRuleCode(), e.getRuleVersion(), e.getDocId(),
                e.getInputSnapshot(), e.getIntermediatesJson(), e.getOutputJson(),
                RuleComputation.ComputationStatus.valueOf(e.getStatus()), e.getError(),
                e.getDurationMs(), e.getTraceId(), e.getComputedAt());
    }

    private static String truncate(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }
}
