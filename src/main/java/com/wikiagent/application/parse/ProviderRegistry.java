package com.wikiagent.application.parse;

import com.wikiagent.config.ParseProperties;
import com.wikiagent.domain.parse.spi.Capability;
import com.wikiagent.domain.parse.spi.DocumentAiProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 供应商注册表：按 {@code wikiagent.parse.provider} 选定具名供应商，
 * 再按 capability + available 过滤。provider=none 或无供应商可用时返回 empty。
 */
@Component
public class ProviderRegistry {

    private final List<DocumentAiProvider> providers;
    private final ParseProperties props;

    public ProviderRegistry(List<DocumentAiProvider> providers, ParseProperties props) {
        this.providers = providers;
        this.props = props;
    }

    /** 选中的供应商是否具备且当前可用某能力。 */
    public boolean supports(Capability capability) {
        return forCapability(capability).isPresent();
    }

    public Optional<DocumentAiProvider> forCapability(Capability capability) {
        String name = props.getProvider();
        if (name == null || name.isBlank() || "none".equalsIgnoreCase(name)) {
            return Optional.empty();
        }
        return providers.stream()
                .filter(p -> name.equalsIgnoreCase(p.name()))
                .filter(DocumentAiProvider::available)
                .filter(p -> p.capabilities().contains(capability))
                .findFirst();
    }
}
