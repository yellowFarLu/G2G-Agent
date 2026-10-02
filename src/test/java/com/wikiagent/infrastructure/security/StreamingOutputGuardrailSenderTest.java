package com.wikiagent.infrastructure.security;

import com.wikiagent.application.gateway.GatewayAuditService;
import com.wikiagent.infrastructure.gateway.GuardrailAdvisorChain;
import com.wikiagent.infrastructure.gateway.GuardrailDetector;
import com.wikiagent.infrastructure.gateway.GuardrailResult;
import com.wikiagent.service.chat.SseSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AC-J2 流式输出网关装饰器单测：
 * ① 正常答案 → done 转发、最终答案回调；
 * ② 输出违规 → done 不转发、改发 blocked（含 detector/reason/violationType）、complete、blocked 回调；
 * ③ SANITIZE → done 转发且落库文本为脱敏文本（不含原文敏感串）；
 * ④ 空答案直接放行（保持历史行为，不回调）；
 * ⑤ 重复 done 只检测一次。
 */
class StreamingOutputGuardrailSenderTest {

    private SseSender delegate;
    private GatewayAuditService auditService;
    private RecordingFinalizer finalizer;

    /** 可控输出检测器：命中关键字 BLOCKME 阻断；命中手机号脱敏。 */
    private static class StubOutputDetector implements GuardrailDetector {
        private final String name;
        private int calls;

        private StubOutputDetector(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String direction() {
            return "OUTPUT";
        }

        @Override
        public GuardrailResult check(String content, String userId, String sessionId) {
            calls++;
            if (content.contains("BLOCKME")) {
                return GuardrailResult.block(name, 0.99, "stub 命中违规标记 BLOCKME", "TOXIC_CONTENT");
            }
            if (content.contains("13800138000")) {
                return GuardrailResult.sanitize(name, 0.5, "stub 手机号脱敏",
                        content.replace("13800138000", "138****8000"), "PII_LEAK");
            }
            return GuardrailResult.pass(name);
        }
    }

    static final class RecordingFinalizer implements StreamingOutputGuardrailSender.AnswerFinalizer {
        String answer;
        String blockedType;
        String blockedReason;

        @Override
        public void onAnswer(String sessionId, String finalAnswer) {
            this.answer = finalAnswer;
        }

        @Override
        public void onBlocked(String sessionId, String violationType, String reason) {
            this.blockedType = violationType;
            this.blockedReason = reason;
        }
    }

    @BeforeEach
    void setUp() {
        delegate = mock(SseSender.class);
        auditService = mock(GatewayAuditService.class);
        when(auditService.logAudit(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyDouble(), any(), any(), anyString())).thenReturn(1L);
        finalizer = new RecordingFinalizer();
    }

    private StreamingOutputGuardrailSender senderWith(GuardrailDetector... detectors) {
        GuardrailAdvisorChain chain = new GuardrailAdvisorChain(List.of(detectors), auditService,
                true, true);
        return new StreamingOutputGuardrailSender(delegate, chain, "u1", "s1", finalizer);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 正常答案done转发且回调最终答案() {
        StreamingOutputGuardrailSender sender = senderWith(new StubOutputDetector("stub_out"));
        sender.send("delta", Map.of("text", "正常"));
        sender.send("delta", Map.of("text", "答案"));
        sender.send("done", Map.of());

        verify(delegate).send("done", Map.of());
        verify(delegate, never()).send(org.mockito.ArgumentMatchers.eq("blocked"), any());
        assertThat(finalizer.answer).isEqualTo("正常答案");
        assertThat(finalizer.blockedType).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 违规答案不发done改发blocked载荷并结束() {
        StreamingOutputGuardrailSender sender = senderWith(new StubOutputDetector("stub_out"));
        sender.send("delta", Map.of("text", "前文 "));
        sender.send("delta", Map.of("text", "BLOCKME 违规内容"));
        sender.send("done", Map.of());

        verify(delegate, never()).send(org.mockito.ArgumentMatchers.eq("done"), any());
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(delegate).send(org.mockito.ArgumentMatchers.eq("blocked"), payload.capture());
        Map<String, Object> body = (Map<String, Object>) payload.getValue();
        assertThat(body).containsKeys("reason", "violationType", "detector");
        assertThat(body.get("violationType")).isEqualTo("TOXIC_CONTENT");
        assertThat(String.valueOf(body.get("reason"))).contains("BLOCKME");
        assertThat(body.get("detector")).isEqualTo(StreamingOutputGuardrailSender.DETECTOR);
        verify(delegate).complete();
        assertThat(finalizer.answer).isNull();
        assertThat(finalizer.blockedType).isEqualTo("TOXIC_CONTENT");
    }

    @Test
    void 脱敏答案done转发且回调文本不含原始手机号() {
        StubOutputDetector detector = new StubOutputDetector("stub_out");
        StreamingOutputGuardrailSender sender = senderWith(detector);
        sender.send("delta", Map.of("text", "联系电话：13800138000 请致电"));
        sender.send("done", Map.of());

        verify(delegate).send("done", Map.of());
        assertThat(finalizer.answer).contains("138****8000").doesNotContain("13800138000");
        assertThat(finalizer.blockedType).isNull();
    }

    @Test
    void 空答案直接放行不触发检测与回调() {
        StubOutputDetector detector = new StubOutputDetector("stub_out");
        StreamingOutputGuardrailSender sender = senderWith(detector);
        sender.send("done", Map.of());

        verify(delegate).send("done", Map.of());
        assertThat(detector.calls).isZero();
        assertThat(finalizer.answer).isNull();
        assertThat(finalizer.blockedType).isNull();
    }

    @Test
    void 重复done只检测一次() {
        StubOutputDetector detector = new StubOutputDetector("stub_out");
        StreamingOutputGuardrailSender sender = senderWith(detector);
        sender.send("delta", Map.of("text", "普通文本"));
        sender.send("done", Map.of());
        sender.send("done", Map.of());

        assertThat(detector.calls).isEqualTo(1);
        verify(delegate, times(1)).send(org.mockito.ArgumentMatchers.eq("done"), any());
    }

    @Test
    void 非delta与done事件原样透传() {
        StreamingOutputGuardrailSender sender = senderWith(new StubOutputDetector("stub_out"));
        sender.send("stage", Map.of("stage", "retrieving"));
        sender.send("sources", List.of());

        verify(delegate).send(org.mockito.ArgumentMatchers.eq("stage"), any());
        verify(delegate).send(org.mockito.ArgumentMatchers.eq("sources"), any());
        verify(delegate, never()).send(org.mockito.ArgumentMatchers.eq("done"), any());
    }
}
