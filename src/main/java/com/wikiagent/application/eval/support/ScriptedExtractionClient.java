package com.wikiagent.application.eval.support;

import com.wikiagent.infrastructure.extract.LlmFieldExtractionClient;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

/**
 * 离线评测专用抽取客户端：对任意提示词返回固定录制固件
 * （{@code fixtures/extract/invoice-low-confidence.json}，含一个 0.42 低置信字段），
 * 不触达任何真实 ChatModel（super(null)，覆写 call）。
 * <p>
 * 供异常样本 LOW_CONFIDENCE 走<b>真实</b> FieldExtractionService 校验流水线，
 * 由其真实逻辑产出 needsReview=true 信号，再经 AnomalyClassifier 映射。
 */
public class ScriptedExtractionClient extends LlmFieldExtractionClient {

    private final String recordedAnswer;

    public ScriptedExtractionClient() {
        this("fixtures/extract/invoice-low-confidence.json");
    }

    public ScriptedExtractionClient(String classpathFixture) {
        super(null); // 不触达真实 ChatModel
        try {
            this.recordedAnswer = new ClassPathResource(classpathFixture)
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("加载评测抽取录制固件失败: " + classpathFixture, e);
        }
    }

    @Override
    public String call(String systemPrompt, String documentText, String repairFeedback) {
        return recordedAnswer;
    }
}
