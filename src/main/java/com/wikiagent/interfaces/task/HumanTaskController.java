package com.wikiagent.interfaces.task;

import com.wikiagent.application.task.HumanTaskService;
import com.wikiagent.domain.task.HumanTaskKind;
import com.wikiagent.interfaces.task.dto.ClaimRequest;
import com.wikiagent.interfaces.task.dto.HumanTaskView;
import com.wikiagent.interfaces.task.dto.ResolveTaskRequest;
import com.wikiagent.interfaces.task.dto.TaskView;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 人工接管 REST API（规格 4.2）：claim（lock_version CAS）、resolve（INPUT 续跑 / DIRECT_RESOLVE 终结）。
 * enabled=false 时统一 503。
 */
@RestController
public class HumanTaskController {

    private final ObjectProvider<HumanTaskService> service;

    public HumanTaskController(ObjectProvider<HumanTaskService> service) {
        this.service = service;
    }

    @PostMapping("/api/human-tasks/{id}/claim")
    public HumanTaskView claim(@PathVariable Long id,
                               @RequestBody(required = false) ClaimRequest body,
                               @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        return HumanTaskView.from(available().claim(id, userId,
                body == null ? null : body.lockVersion()));
    }

    @PostMapping("/api/human-tasks/{id}/resolve")
    public TaskView resolve(@PathVariable Long id,
                            @RequestBody ResolveTaskRequest body,
                            @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        if (body == null || body.kind() == null) {
            throw new IllegalArgumentException("kind 必填（INPUT / DIRECT_RESOLVE）");
        }
        HumanTaskKind kind = HumanTaskKind.valueOf(body.kind());
        return TaskView.from(available().resolve(id, userId, kind, body.formValue(), body.resultRef()));
    }

    private HumanTaskService available() {
        HumanTaskService s = service.getIfAvailable();
        if (s == null) {
            throw new IllegalStateException("任务框架未开启（wikiagent.task.enabled=false）");
        }
        return s;
    }
}
