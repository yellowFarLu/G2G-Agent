package com.wikiagent.application.parse;

import com.wikiagent.config.ParseProperties;
import com.wikiagent.domain.parse.spi.AudioRequest;
import com.wikiagent.domain.parse.spi.Capability;
import com.wikiagent.domain.parse.spi.DocumentAiProvider;
import com.wikiagent.domain.parse.spi.LayoutRequest;
import com.wikiagent.domain.parse.spi.LayoutResult;
import com.wikiagent.domain.parse.spi.OcrRequest;
import com.wikiagent.domain.parse.spi.OcrResult;
import com.wikiagent.domain.parse.spi.TableRequest;
import com.wikiagent.domain.parse.spi.TableResult;
import com.wikiagent.domain.parse.spi.TranscriptResult;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-1 注册表选择规则：按名选定 → available 过滤 → capability 过滤；none 全部禁用。
 */
class ProviderRegistryTest {

    static final class FakeProvider implements DocumentAiProvider {
        private final String name;
        private final boolean available;
        private final Set<Capability> caps;

        FakeProvider(String name, boolean available, Set<Capability> caps) {
            this.name = name;
            this.available = available;
            this.caps = caps;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Set<Capability> capabilities() {
            return caps;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public OcrResult ocr(OcrRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TranscriptResult transcribe(AudioRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LayoutResult layout(LayoutRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TableResult table(TableRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    private ParseProperties props(String provider) {
        ParseProperties p = new ParseProperties();
        p.setProvider(provider);
        return p;
    }

    @Test
    void selectsNamedProviderByCapabilityAndAvailability() {
        DocumentAiProvider dash = new FakeProvider("dashscope", true,
                Set.of(Capability.OCR, Capability.LAYOUT, Capability.TABLE));
        DocumentAiProvider baidu = new FakeProvider("baidu", true, Set.of(Capability.OCR));
        ProviderRegistry registry = new ProviderRegistry(
                java.util.List.of(dash, baidu), props("baidu"));

        assertThat(registry.supports(Capability.OCR)).isTrue();
        assertThat(registry.forCapability(Capability.OCR)).containsSame(baidu);
        // 百度桩没有 LAYOUT 能力
        assertThat(registry.supports(Capability.LAYOUT)).isFalse();
    }

    @Test
    void noneProviderDisablesAllCapabilities() {
        DocumentAiProvider dash = new FakeProvider("dashscope", true, Set.of(Capability.values()));
        ProviderRegistry registry = new ProviderRegistry(
                java.util.List.of(dash), props("none"));

        assertThat(registry.supports(Capability.OCR)).isFalse();
        assertThat(registry.forCapability(Capability.ASR)).isEmpty();
    }

    @Test
    void unavailableProviderIsSkipped() {
        DocumentAiProvider dash = new FakeProvider("dashscope", false, Set.of(Capability.OCR));
        ProviderRegistry registry = new ProviderRegistry(
                java.util.List.of(dash), props("dashscope"));

        assertThat(registry.supports(Capability.OCR)).isFalse();
    }
}
