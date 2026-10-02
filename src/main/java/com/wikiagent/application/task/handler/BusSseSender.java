package com.wikiagent.application.task.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.application.task.StreamEvent;
import com.wikiagent.application.task.TaskStreamBus;
import com.wikiagent.service.chat.SseSender;

/**
 * bus 转发型 SseSender（Task 12）：worker 侧 handler 把 PERO 的 delta/done 事件
 * 转发到 {@link TaskStreamBus}（type=delta/done/error），由 SSE 桥写给真实客户端。
 * 不持有真实 emitter，故 super(null) 且 complete 为空操作（终态事件由 TaskWorker 统一发布）。
 */
final class BusSseSender extends SseSender {

    private final TaskStreamBus bus;
    private final String taskId;
    private final ObjectMapper mapper;

    BusSseSender(TaskStreamBus bus, String taskId, ObjectMapper mapper) {
        super(null);
        this.bus = bus;
        this.taskId = taskId;
        this.mapper = mapper;
    }

    @Override
    public boolean send(String event, Object data) {
        bus.publish(new StreamEvent(taskId, event, mapper.valueToTree(data)));
        return true;
    }

    @Override
    public void complete() {
        // bus 侧无连接可关：done/error 终态事件由 TaskWorker 统一发布
    }
}
