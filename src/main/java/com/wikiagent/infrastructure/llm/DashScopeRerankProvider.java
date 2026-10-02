package com.wikiagent.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wikiagent.domain.llm.spi.RerankProvider;
import com.wikiagent.domain.llm.spi.RerankRequest;
import com.wikiagent.domain.llm.spi.RerankResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * DashScope text-rerank 适配器（REST 实装）。
 * <p>
 * 契约（DashScope 2026 文档）：
 * <pre>
 * POST {base-url}
 * Header: Authorization: Bearer {apiKey}, Content-Type: application/json
 * Body : {"model": model,
 *         "input": {"query": q, "documents": [{"text": "..."}, ...]},
 *         "parameters": {"top_n": n, "return_documents": false}}
 * 返回 : {"output": {"results": [{"index": 原候选下标, "relevance_score": 0.9}, ...]}}
 * </pre>
 * 解析时按 {@code index} 把分数对齐回请求 documents 顺序（缺项记 0 分），
 * 保持 {@link RerankResult#scores()} 与请求候选顺序对齐的既有契约。
 * <p>
 * 可用性：{@code wikiagent.rerank.enabled=true} 且 API Key 非空时 {@link #available()} 为 true；
 * HTTP/解析错误统一抛 {@link RerankProviderException}，由上层保持原序降级。
 * <p>
 * 另保留 {@code (model, available)} 占位构造：无 HTTP 客户端、rerank 返回等长零分，
 * 仅供旧桩测试与离线占位场景使用（生产装配不使用该构造）。
 */
public class DashScopeRerankProvider implements RerankProvider {

    private static final Logger log = LoggerFactory.getLogger(DashScopeRerankProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 默认服务地址（可由 wikiagent.rerank.base-url 覆盖）。 */
    public static final String DEFAULT_BASE_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    private final String model;
    private final String apiKey;
    private final RestClient restClient;
    private final boolean enabled;

    /** 占位构造标志：true 时不发起 HTTP，rerank 返回等长零分（旧契约/离线桩）。 */
    private final boolean placeholder;
    private final boolean placeholderAvailable;

    /** 生产构造：按 key/开关/地址创建 RestClient。 */
    public DashScopeRerankProvider(String apiKey, boolean enabled, String baseUrl, String model) {
        this(apiKey, enabled,
                RestClient.builder()
                        .baseUrl(baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl)
                        .build(),
                model);
    }

    /**
     * 测试构造：注入 MockRestServiceServer 绑定的 RestClient（跨包测试需要，故 public；
     * 生产装配使用 {@link #DashScopeRerankProvider(String, boolean, String, String)}）。
     */
    public DashScopeRerankProvider(String apiKey, boolean enabled, RestClient restClient, String model) {
        this.model = model == null ? "gte-rerank" : model;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.enabled = enabled;
        this.restClient = restClient;
        this.placeholder = false;
        this.placeholderAvailable = false;
    }

    /**
     * 旧占位构造（保留既有契约）：不发起 HTTP，{@link #rerank} 恒返回与候选等长的 0 分列表。
     *
     * @param available 占位 available 标志（直接透传）
     */
    public DashScopeRerankProvider(String model, boolean available) {
        this.model = model == null ? "gte-rerank" : model;
        this.apiKey = "";
        this.restClient = null;
        this.enabled = false;
        this.placeholder = true;
        this.placeholderAvailable = available;
    }

    @Override
    public RerankResult rerank(RerankRequest request) {
        int n = request == null || request.documents() == null ? 0 : request.documents().size();
        if (placeholder) {
            // 占位实现：与候选等长的 0 分列表，保证桩测试契约
            return new RerankResult(Collections.nCopies(n, 0.0));
        }
        if (n == 0) {
            return new RerankResult(List.of());
        }
        if (!available()) {
            throw new RerankProviderException("rerank 不可用（未启用或未配置 API Key）");
        }
        int topN = request.topN() <= 0 ? n : Math.min(request.topN(), n);
        String body = buildRequestBody(request, topN);
        long started = System.currentTimeMillis();
        try {
            String response = restClient.post()
                    .uri("")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            List<Double> scores = parseScores(response, n);
            log.debug("DashScope rerank 成功 n={} 耗时={}ms", n, System.currentTimeMillis() - started);
            return new RerankResult(scores);
        } catch (RerankProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new RerankProviderException("DashScope rerank 调用失败: " + e.getMessage(), e);
        }
    }

    /** 组装请求体：model + input{query,documents[{text}]} + parameters{top_n,return_documents=false}。 */
    private String buildRequestBody(RerankRequest request, int topN) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        ObjectNode input = root.putObject("input");
        input.put("query", request.query() == null ? "" : request.query());
        ArrayNode documents = input.putArray("documents");
        for (String doc : request.documents()) {
            documents.addObject().put("text", doc == null ? "" : doc);
        }
        ObjectNode parameters = root.putObject("parameters");
        parameters.put("top_n", topN);
        parameters.put("return_documents", false);
        return root.toString();
    }

    /**
     * 解析 output.results[{index,relevance_score}]，按 index 对齐回请求顺序。
     * 缺/越界下标忽略，对应候选保持 0 分；响应结构缺失抛统一异常。
     */
    private List<Double> parseScores(String response, int n) {
        if (response == null || response.isBlank()) {
            throw new RerankProviderException("rerank 响应为空");
        }
        try {
            JsonNode results = MAPPER.readTree(response).path("output").path("results");
            if (!results.isArray()) {
                throw new RerankProviderException("rerank 响应缺少 output.results: " + response);
            }
            List<Double> scores = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                scores.add(0.0);
            }
            for (JsonNode item : results) {
                int idx = item.path("index").asInt(-1);
                if (idx < 0 || idx >= n) {
                    continue;
                }
                scores.set(idx, item.path("relevance_score").asDouble(0.0));
            }
            return scores;
        } catch (RerankProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new RerankProviderException("rerank 响应解析失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean available() {
        if (placeholder) {
            return placeholderAvailable;
        }
        return enabled && !apiKey.isBlank();
    }

    /** 实际模型名（打点用）。 */
    public String model() {
        return model;
    }

    @Override
    public String name() {
        return "dashscope-rerank:" + model;
    }
}
