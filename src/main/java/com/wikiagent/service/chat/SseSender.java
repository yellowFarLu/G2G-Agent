package com.wikiagent.service.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SseEmitter 的安全封装：发送失败（如客户端断开）不抛出，返回 false 由调用方决定停止生成。
 */
public class SseSender {

    private static final Logger log = LoggerFactory.getLogger(SseSender.class);

    private final SseEmitter emitter;

    public SseSender(SseEmitter emitter) {
        this.emitter = emitter;
    }

    /** 返回 false 表示客户端已断开等发送失败。 */
    public boolean send(String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
            return true;
        } catch (Exception e) {
            log.debug("SSE 发送失败（客户端可能已断开）: {}", e.getMessage());
            return false;
        }
    }

    public void complete() {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("SSE complete 失败: {}", e.getMessage());
        }
    }
}
