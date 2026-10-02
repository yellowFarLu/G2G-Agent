package com.wikiagent.application.llm;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * E4 模型单价配置服务：{@code wikiagent.llm.pricing.{model}=tokensInPrice:tokensOutPrice}
 * （单位：元 / 1K tokens）。未配置的模型返回 null（成本不可估算，不阻断打点）。
 */
@Service
public class ModelPricingService {

    private final Environment env;

    public ModelPricingService(Environment env) {
        this.env = env;
    }

    /**
     * 估算单次调用成本（元）。
     *
     * @return 单价配置缺失或 token 数为空时返回 null
     */
    public Double estimate(String model, Integer tokensIn, Integer tokensOut) {
        if (model == null || tokensIn == null || tokensOut == null) {
            return null;
        }
        String raw = env.getProperty("wikiagent.llm.pricing." + model);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(":");
        if (parts.length != 2) {
            return null;
        }
        try {
            double inPrice = Double.parseDouble(parts[0].trim());
            double outPrice = Double.parseDouble(parts[1].trim());
            return (tokensIn / 1000.0) * inPrice + (tokensOut / 1000.0) * outPrice;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
