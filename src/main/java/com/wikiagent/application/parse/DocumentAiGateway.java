package com.wikiagent.application.parse;

import com.wikiagent.config.ParseProperties;
import com.wikiagent.domain.parse.spi.AudioRequest;
import com.wikiagent.domain.parse.spi.Capability;
import com.wikiagent.domain.parse.spi.DocumentAiProvider;
import com.wikiagent.domain.parse.spi.LayoutRequest;
import com.wikiagent.domain.parse.spi.LayoutResult;
import com.wikiagent.domain.parse.spi.OcrRequest;
import com.wikiagent.domain.parse.spi.OcrResult;
import com.wikiagent.domain.parse.spi.ParseProviderException;
import com.wikiagent.domain.parse.spi.TableRequest;
import com.wikiagent.domain.parse.spi.TableResult;
import com.wikiagent.domain.parse.spi.TranscriptResult;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 文档 AI 统一门面：流水线只依赖本类（注册表选择 + 执行器超时/重试/熔断），
 * 不直接接触任何供应商实现。
 */
@Component
public class DocumentAiGateway {

    private final ProviderRegistry registry;
    private final ProviderExecutor executor;
    private final ParseProperties props;

    public DocumentAiGateway(ProviderRegistry registry, ProviderExecutor executor, ParseProperties props) {
        this.registry = registry;
        this.executor = executor;
        this.props = props;
    }

    public boolean has(Capability capability) {
        return registry.supports(capability);
    }

    public Optional<OcrResult> ocrOptional(OcrRequest request) {
        return registry.forCapability(Capability.OCR)
                .map(p -> executor.execute(Capability.OCR, props.getOcrTimeoutSec(), () -> p.ocr(request)));
    }

    public OcrResult ocrOrThrow(OcrRequest request) {
        DocumentAiProvider p = registry.forCapability(Capability.OCR)
                .orElseThrow(() -> new ParseProviderException("OCR 能力不可用（未配置/凭证缺失/provider=none）", false));
        return executor.execute(Capability.OCR, props.getOcrTimeoutSec(), () -> p.ocr(request));
    }

    public Optional<TranscriptResult> transcribeOptional(AudioRequest request) {
        return registry.forCapability(Capability.ASR)
                .map(p -> executor.execute(Capability.ASR, props.getAsrTimeoutSec(), () -> p.transcribe(request)));
    }

    public Optional<LayoutResult> layoutOptional(LayoutRequest request) {
        return registry.forCapability(Capability.LAYOUT)
                .map(p -> executor.execute(Capability.LAYOUT, props.getLayoutTimeoutSec(), () -> p.layout(request)));
    }

    public Optional<TableResult> tableOptional(TableRequest request) {
        return registry.forCapability(Capability.TABLE)
                .map(p -> executor.execute(Capability.TABLE, props.getTableTimeoutSec(), () -> p.table(request)));
    }
}
