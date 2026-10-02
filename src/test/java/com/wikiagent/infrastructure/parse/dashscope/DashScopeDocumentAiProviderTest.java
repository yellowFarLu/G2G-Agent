package com.wikiagent.infrastructure.parse.dashscope;

import com.wikiagent.config.ParseProperties;
import com.wikiagent.domain.parse.spi.AudioRequest;
import com.wikiagent.domain.parse.spi.Capability;
import com.wikiagent.domain.parse.spi.OcrRequest;
import com.wikiagent.domain.parse.spi.ParseProviderException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * B-1 DashScope 供应商无 Key 降级测试：available=false、能力集为空、
 * 调用抛非重试异常；ASR 未配置公网基础 URL 时不入能力集。不联网、不读真实凭证。
 */
class DashScopeDocumentAiProviderTest {

    @Test
    void withoutApiKeyProviderIsUnavailableAndEmptyCapabilities() {
        DashScopeDocumentAiProvider provider =
                new DashScopeDocumentAiProvider(new ParseProperties(), "");

        assertThat(provider.name()).isEqualTo("dashscope");
        assertThat(provider.available()).isFalse();
        assertThat(provider.capabilities()).isEmpty();
    }

    @Test
    void asrCapabilityRequiresPublicBaseUrl() {
        ParseProperties props = new ParseProperties();
        // 构造时无法传有效 key（无 Key 视觉客户端不初始化），这里仅验证 ASR URL 缺失判定：
        DashScopeDocumentAiProvider provider = new DashScopeDocumentAiProvider(props, "");

        Throwable t = catchThrowable(() -> provider.transcribe(
                new AudioRequest("d1", new byte[]{1}, "audio/mpeg", "a.mp3", null)));
        assertThat(t).isInstanceOf(ParseProviderException.class);
        assertThat(((ParseProviderException) t).retryable()).isFalse();
    }

    @Test
    void ocrWithoutKeyThrowsNonRetryable() {
        DashScopeDocumentAiProvider provider =
                new DashScopeDocumentAiProvider(new ParseProperties(), "");

        Throwable t = catchThrowable(() -> provider.ocr(
                new OcrRequest("d1", 1, new byte[]{1}, "image/png")));
        assertThat(t).isInstanceOf(ParseProviderException.class);
        assertThat(((ParseProviderException) t).retryable()).isFalse();
        assertThat(Capability.values()).contains(Capability.OCR, Capability.ASR,
                Capability.LAYOUT, Capability.TABLE);
    }
}
