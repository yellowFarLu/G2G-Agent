package com.wikiagent.controller;

import com.wikiagent.service.store.MilvusStoreService;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 健康检查：如实反馈各依赖状态，不粉饰。
 * - dashscope: API Key 是否已配置 + 使用模型名（不做真实调用，避免健康检查产生费用）
 * - milvus: 真实连接探测（带 3 秒超时，返回服务端版本或错误信息）
 */
@RestController
public class HealthController {

    private final MilvusStoreService milvus;
    private final Environment env;
    private final ExecutorService probe = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "milvus-health-probe");
        t.setDaemon(true);
        return t;
    });

    public HealthController(MilvusStoreService milvus, Environment env) {
        this.milvus = milvus;
        this.env = env;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");

        String apiKey = env.getProperty("spring.ai.dashscope.api-key", "");
        boolean keyConfigured = apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("${");
        Map<String, Object> dashscope = new LinkedHashMap<>();
        dashscope.put("apiKeyConfigured", keyConfigured);
        dashscope.put("chatModel", env.getProperty("spring.ai.dashscope.chat.options.model", ""));
        dashscope.put("embeddingModel", env.getProperty("spring.ai.dashscope.embedding.options.model", ""));
        result.put("dashscope", dashscope);

        Map<String, Object> m = new LinkedHashMap<>();
        Future<String> f = probe.submit(milvus::serverVersion);
        try {
            m.put("status", "UP");
            m.put("version", f.get(3, TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            f.cancel(true);
            m.put("status", "DOWN");
            m.put("error", "连接探测超过 3 秒（服务不可达或网络受限）");
        } catch (Exception e) {
            m.put("status", "DOWN");
            String err = milvus.lastError();
            m.put("error", err == null || err.isBlank() ? e.getMessage() : err);
        }
        result.put("milvus", m);
        return result;
    }
}
