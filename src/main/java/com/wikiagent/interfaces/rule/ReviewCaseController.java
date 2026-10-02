package com.wikiagent.interfaces.rule;

import com.wikiagent.application.rule.ReviewCaseService;
import com.wikiagent.domain.rule.ReviewCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 复核案件 REST API：列表 / 详情 / 处置。
 */
@RestController
@RequestMapping("/api/review-cases")
public class ReviewCaseController {

    private final ReviewCaseService service;

    public ReviewCaseController(ReviewCaseService service) {
        this.service = service;
    }

    public record ResolveRequest(String action, Map<String, String> editedFields, String comment) {
    }

    @GetMapping
    public ResponseEntity<List<ReviewCase>> list(@RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String docId) {
        return ResponseEntity.ok(service.list(status, docId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReviewCase> get(@PathVariable Long id) {
        return ResponseEntity.ok(service.get(id));
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<ReviewCase> resolve(@PathVariable Long id,
                                              @RequestBody ResolveRequest body,
                                              @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        if (body == null || body.action() == null || body.action().isBlank()) {
            throw new IllegalArgumentException("action 必填（APPROVE / REJECT / EDIT）");
        }
        ReviewCase.ReviewAction action = ReviewCase.ReviewAction.valueOf(body.action());
        return ResponseEntity.ok(service.dispose(id, action, body.editedFields(), userId));
    }
}
