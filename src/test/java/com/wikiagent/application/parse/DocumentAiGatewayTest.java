package com.wikiagent.application.parse;

import com.wikiagent.config.ParseProperties;
import com.wikiagent.domain.parse.spi.OcrRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B-1 门面：无可用能力时 Optional 为空；ocrOrThrow 抛非重试异常。
 */
class DocumentAiGatewayTest {

    @Test
    void returnsEmptyOptionalWhenCapabilityMissing() {
        ParseProperties props = new ParseProperties();
        ProviderRegistry emptyRegistry = new ProviderRegistry(List.of(), props);
        DocumentAiGateway gateway = new DocumentAiGateway(
                emptyRegistry, new ProviderExecutor(props), props);

        assertThat(gateway.has(com.wikiagent.domain.parse.spi.Capability.OCR)).isFalse();
        assertThat(gateway.ocrOptional(new OcrRequest("d1", 1, new byte[]{1}, "image/png")))
                .isEmpty();
    }

    @Test
    void ocrOrThrowsWhenUnavailable() {
        @SuppressWarnings("unchecked")
        ProviderRegistry registry = mock(ProviderRegistry.class);
        ParseProperties props = new ParseProperties();
        when(registry.forCapability(com.wikiagent.domain.parse.spi.Capability.OCR))
                .thenReturn(java.util.Optional.empty());

        DocumentAiGateway gateway = new DocumentAiGateway(registry, new ProviderExecutor(props), props);

        Throwable t = catchThrowable(() -> gateway.ocrOrThrow(
                new OcrRequest("d1", 1, new byte[]{1}, "image/png")));
        assertThat(t).hasMessageContaining("OCR 能力不可用");
        assertThat(((com.wikiagent.domain.parse.spi.ParseProviderException) t).retryable()).isFalse();
    }
}
