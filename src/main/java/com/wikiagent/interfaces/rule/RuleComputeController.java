package com.wikiagent.interfaces.rule;

import com.wikiagent.application.rule.RuleExecutionService;
import com.wikiagent.domain.rule.RuleComputation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 规则计算 REST API：重算 / 计算详情 / 跨版本回放。
 */
@RestController
@RequestMapping("/api/rules")
public class RuleComputeController {

    private final RuleExecutionService executionService;

    public RuleComputeController(RuleExecutionService executionService) {
        this.executionService = executionService;
    }

    public record ComputeRequest(String docId, Map<String, Object> input) {
    }

    public record ReplayRequest(Integer targetVersion) {
    }

    @PostMapping("/{code}/compute")
    public ResponseEntity<RuleComputation> compute(@PathVariable String code,
                                                   @RequestBody ComputeRequest body) {
        if (body == null) {
            throw new IllegalArgumentException("请求体必填");
        }
        Map<String, Object> input = body.input() == null ? Map.of() : body.input();
        if (body.docId() != null && !body.docId().isBlank()) {
            return ResponseEntity.ok(executionService.executeAndApply(body.docId(), code, input));
        }
        return ResponseEntity.ok(executionService.execute(code, null, input));
    }

    @GetMapping("/computations/{id}")
    public ResponseEntity<RuleComputation> getComputation(@PathVariable Long id) {
        return ResponseEntity.ok(executionService.getComputation(id));
    }

    @PostMapping("/computations/{id}/replay")
    public ResponseEntity<Map<String, Object>> replay(@PathVariable Long id,
                                                      @RequestBody ReplayRequest body) {
        if (body == null || body.targetVersion() == null) {
            throw new IllegalArgumentException("targetVersion 必填");
        }
        return ResponseEntity.ok(executionService.replay(id, body.targetVersion()));
    }
}
