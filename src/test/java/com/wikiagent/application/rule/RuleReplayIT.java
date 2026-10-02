package com.wikiagent.application.rule;

import com.wikiagent.domain.rule.RuleComputation;
import com.wikiagent.domain.rule.dsl.RuleEngineException;
import com.wikiagent.entity.rule.RuleComputationEntity;
import com.wikiagent.repo.rule.RuleComputationRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * AC-D1 跨版本回放：v1 计算 → v2 改 DSL 发布 → replay 到 v2 → diff 显示字段 changed；旧 computation 仍可查。
 */
@SpringBootTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
class RuleReplayIT {

    @Autowired
    private RuleSetService ruleSetService;

    @Autowired
    private RuleExecutionService executionService;

    @Autowired
    private RuleComputationRepo computationRepo;

    @Test
    void replayAcrossVersionsShowsDiff() {
        String code = "replay-" + System.nanoTime();
        String dslV1 = """
                {"steps":[{"op":"const","params":{"value":"v1-tag"},"to":"tag"}],"outputs":["tag"]}
                """;
        ruleSetService.createDraft(code, dslV1, "回放测试", "test");
        ruleSetService.publish(code, 1);

        RuleComputation origin = executionService.execute(code, null, Map.of());
        assertThat(origin.status()).isEqualTo(RuleComputation.ComputationStatus.SUCCESS);
        assertThat(origin.ruleVersion()).isEqualTo(1);

        // 建 v2（改 const 值）并发布
        String dslV2 = """
                {"steps":[{"op":"const","params":{"value":"v2-tag"},"to":"tag"}],"outputs":["tag"]}
                """;
        ruleSetService.createDraft(code, dslV2, "回放测试 v2", "test");
        ruleSetService.publish(code, 2);

        // replay 到 v2
        Map<String, Object> replay = executionService.replay(origin.id(), 2);
        assertThat(replay.get("replayId")).isNotNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diff = (List<Map<String, Object>>) replay.get("diff");
        assertThat(diff).anyMatch(d -> "tag".equals(d.get("key"))
                && Boolean.TRUE.equals(d.get("changed"))
                && "v1-tag".equals(d.get("old"))
                && "v2-tag".equals(d.get("new")));

        // 旧 computation 仍可查
        RuleComputation fetched = executionService.getComputation(origin.id());
        assertThat(fetched.ruleVersion()).isEqualTo(1);
        assertThat(fetched.outputJson()).contains("v1-tag");
    }

    /**
     * #11 原 computation 的 input_snapshot 损坏：replay 必须抛 RuleEngineException
     * （消息携带新 FAILED computationId），且新 FAILED 行在外层回滚后仍落库，
     * input_snapshot 原样保留损坏串。
     */
    @Test
    void replayCorruptInputSnapshotRecordsFailedAndThrows() {
        String code = "replay-bad-" + System.nanoTime();
        String dsl = """
                {"steps":[{"op":"const","params":{"value":"v1-tag"},"to":"tag"}],"outputs":["tag"]}
                """;
        ruleSetService.createDraft(code, dsl, "回放损坏测试", "test");
        ruleSetService.publish(code, 1);

        String corrupt = "{这不是合法JSON,,,";
        RuleComputationEntity origin = new RuleComputationEntity();
        origin.setRuleCode(code);
        origin.setRuleVersion(1);
        origin.setDocId("doc-bad-" + System.nanoTime());
        origin.setInputSnapshot(corrupt);
        origin.setOutputJson("{\"tag\":\"old\"}");
        origin.setStatus(RuleComputation.ComputationStatus.SUCCESS.name());
        origin.setTraceId(null);
        origin.setComputedAt(Instant.now());
        origin.setDurationMs(0L);
        RuleComputationEntity saved = computationRepo.save(origin);

        RuleEngineException ex = catchThrowableOfType(
                () -> executionService.replay(saved.getId(), 1), RuleEngineException.class);
        assertThat(ex).isNotNull();
        Matcher m = Pattern.compile("FAILED computationId=(\\d+)").matcher(ex.getMessage());
        assertThat(m.find()).as("异常消息必须携带新 FAILED computationId: %s", ex.getMessage())
                .isTrue();
        long failedId = Long.parseLong(m.group(1));

        RuleComputationEntity failed = computationRepo.findById(failedId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(RuleComputation.ComputationStatus.FAILED.name());
        assertThat(failed.getInputSnapshot()).isEqualTo(corrupt);
        assertThat(failed.getRuleVersion()).isEqualTo(1);
        assertThat(failed.getError()).contains("解析失败");

        // 原记录未被破坏
        assertThat(computationRepo.findById(saved.getId()).orElseThrow().getInputSnapshot())
                .isEqualTo(corrupt);
    }
}
