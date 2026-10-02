package com.wikiagent.application.rule;

import com.wikiagent.domain.rule.RuleComputation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
}
