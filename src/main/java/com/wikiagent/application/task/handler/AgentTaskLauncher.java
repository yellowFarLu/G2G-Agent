package com.wikiagent.application.task.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wikiagent.application.task.StreamEvent;
import com.wikiagent.application.task.TaskStreamBus;
import com.wikiagent.application.task.TaskSubmissionService;
import com.wikiagent.domain.task.TaskInstance;
import com.wikiagent.domain.task.TaskPayload;
import com.wikiagent.domain.task.TaskStatus;
import com.wikiagent.domain.task.TaskStep;
import com.wikiagent.domain.task.ports.TaskStepRepositoryPort;
import com.wikiagent.service.chat.SseSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 聊天入口 → 任务框架的薄编排（Task 12）：提交 AGENT 任务并把任务流桥接回
 * 当前聊天 SSE 客户端（前端协议不变：delta/done/error 事件原样转发）。
 * <p>
 * bizKey 幂等：同 (userId, sessionId, userInput) 重复提问命中既有任务——
 * 终态任务直接回放（COMPLETED 回放答案；FAILED/CANCELLED 回放错误），
 * 非终态任务订阅任务流续看。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.task.enabled", havingValue = "true")
public class AgentTaskLauncher {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskLauncher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TaskSubmissionService submission;
    private final TaskStreamBus streamBus;
    private final TaskStepRepositoryPort stepRepo;

    public AgentTaskLauncher(TaskSubmissionService submission,
                             TaskStreamBus streamBus,
                             TaskStepRepositoryPort stepRepo) {
        this.submission = submission;
        this.streamBus = streamBus;
        this.stepRepo = stepRepo;
    }

    /**
     * 提交 AGENT 任务并桥接任务流到当前 SSE 客户端。
     *
     * @return 任务 ID（前端可用于 /api/tasks/{taskId}/stream 续看）
     */
    public String launchAndBridge(String userId, String sessionId, String userInput, SseSender client) {
        ObjectNode args = MAPPER.createObjectNode()
                .put("userId", userId)
                .put("sessionId", sessionId)
                .put("userInput", userInput);
        TaskInstance task = submission.submit(new TaskPayload("AGENT",
                bizKey(userId, sessionId, userInput), userId, null, null, args, null, null));

        if (task.status() == TaskStatus.COMPLETED) {
            replayAnswer(task, client);
            return task.taskId();
        }
        if (task.status() == TaskStatus.FAILED || task.status() == TaskStatus.CANCELLED) {
            String message = "该问题对应任务已" + (task.status() == TaskStatus.FAILED ? "失败" : "取消")
                    + (task.errorMsg() == null ? "" : "：" + task.errorMsg());
            client.send("error", MAPPER.createObjectNode().put("message", message));
            client.complete();
            return task.taskId();
        }
        subscribeAndBridge(task.taskId(), client);
        return task.taskId();
    }

    /** 订阅任务流，delta/done/error 原样转发给当前客户端，done/error 后完成并注销。 */
    private void subscribeAndBridge(String taskId, SseSender client) {
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicReference<AutoCloseable> subRef = new AtomicReference<>();
        Consumer<StreamEvent> forward = ev -> {
            boolean terminal = "done".equals(ev.type()) || "error".equals(ev.type());
            client.send(ev.type(), ev.payload());
            if (terminal && closed.compareAndSet(false, true)) {
                client.complete();
                AutoCloseable sub = subRef.get();
                if (sub != null) {
                    try {
                        sub.close();
                    } catch (Exception ignored) {
                        // 注销失败无影响：emitter 已 complete，后续 send 静默失败
                    }
                }
            }
        };
        subRef.set(streamBus.subscribe(taskId, forward));
    }

    /** COMPLETED 重复提问：从 GENERATE checkpoint 回放答案（delta + done）。 */
    private void replayAnswer(TaskInstance task, SseSender client) {
        String answer = null;
        List<TaskStep> steps = stepRepo.findByTaskIdOrderByStepNo(task.taskId());
        for (TaskStep step : steps) {
            if (AgentTaskHandler.GENERATE_STEP_NO == step.stepNo() && step.checkpoint() != null) {
                try {
                    answer = MAPPER.readTree(step.checkpoint()).path("answer").asText(null);
                } catch (Exception e) {
                    log.warn("任务 {} GENERATE checkpoint 解析失败: {}", task.taskId(), e.getMessage());
                }
            }
        }
        if (answer == null) {
            client.send("error", MAPPER.createObjectNode()
                    .put("message", "历史答案不可读，请换个提问方式"));
            client.complete();
            return;
        }
        client.send("delta", MAPPER.createObjectNode().put("text", answer));
        client.send("done", MAPPER.createObjectNode());
        client.complete();
    }

    /** bizKey=agent:{userId}:{sessionId}:{sha256(userInput)}（Task 12 冻结语义）。 */
    private static String bizKey(String userId, String sessionId, String userInput) {
        return "agent:" + userId + ":" + sessionId + ":" + sha256Hex(userInput);
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(text.getBytes(StandardCharsets.UTF_8))) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
