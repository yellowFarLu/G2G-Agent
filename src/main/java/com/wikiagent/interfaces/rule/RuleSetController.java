package com.wikiagent.interfaces.rule;

import com.wikiagent.application.rule.RuleSetService;
import com.wikiagent.domain.rule.RuleSet;
import com.wikiagent.dto.NotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则集生命周期 REST API：CRUD / 发布 / 归档 / 版本 diff。
 */
@RestController
@RequestMapping("/api/rules")
public class RuleSetController {

    private final RuleSetService service;

    public RuleSetController(RuleSetService service) {
        this.service = service;
    }

    public record CreateRuleRequest(String code, String dslJson, String description) {
    }

    @PostMapping
    public ResponseEntity<RuleSet> createDraft(@RequestBody CreateRuleRequest body,
                                               @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        if (body == null || body.code() == null || body.code().isBlank()
                || body.dslJson() == null || body.dslJson().isBlank()) {
            throw new IllegalArgumentException("code 与 dslJson 必填");
        }
        return ResponseEntity.ok(service.createDraft(body.code(), body.dslJson(),
                body.description(), userId));
    }

    @GetMapping("/{code}")
    public ResponseEntity<List<RuleSet>> listVersions(@PathVariable String code) {
        List<RuleSet> list = service.listByCode(code);
        if (list.isEmpty()) {
            throw new NotFoundException("规则不存在: " + code);
        }
        return ResponseEntity.ok(list);
    }

    @GetMapping("/{code}/active")
    public ResponseEntity<RuleSet> getActive(@PathVariable String code) {
        return ResponseEntity.ok(service.listByCode(code).stream()
                .filter(r -> r.status() == com.wikiagent.domain.rule.RuleStatus.ACTIVE)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("规则无 ACTIVE 版本: " + code)));
    }

    @PostMapping("/{code}/versions/{version}/publish")
    public ResponseEntity<RuleSet> publish(@PathVariable String code, @PathVariable int version) {
        return ResponseEntity.ok(service.publish(code, version));
    }

    @PostMapping("/{code}/versions/{version}/archive")
    public ResponseEntity<RuleSet> archive(@PathVariable String code, @PathVariable int version) {
        return ResponseEntity.ok(service.archive(code, version));
    }

    /** 两版本 DSL 并列对比：checksum 不等即 changed。 */
    @GetMapping("/{code}/diff/{a}/{b}")
    public ResponseEntity<Map<String, Object>> diff(@PathVariable String code,
                                                    @PathVariable int a,
                                                    @PathVariable int b) {
        RuleSet va = service.get(code, a);
        RuleSet vb = service.get(code, b);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("versionA", a);
        result.put("versionB", b);
        result.put("dslA", va.dslJson());
        result.put("dslB", vb.dslJson());
        result.put("checksumA", va.checksum());
        result.put("checksumB", vb.checksum());
        result.put("changed", !va.checksum().equals(vb.checksum()));
        return ResponseEntity.ok(result);
    }
}
