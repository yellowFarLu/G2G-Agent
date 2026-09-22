package com.wikiagent.infrastructure.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.wikiagent.service.chat.SseSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * v3 §7.4 Mock 意图模拟器。
 * <p>
 * 对 4 个 Mock 意图（ai_coding / customer_intake / business_rule_config / order_query）
 * 模拟真实业务流程：分阶段推进 + SSE 事件流 + 占位数据填充 + 响应模板渲染。
 * <p>
 * 开关：{@code wikiagent.routing.mock-intents-enabled=false} 时 4 个 Mock 意图降级为 knowledge_qa。
 */
@Component
public class MockIntentSimulator {

    private static final Logger log = LoggerFactory.getLogger(MockIntentSimulator.class);
    private static final Random random = ThreadLocalRandom.current();

    private final MockScenarioTemplateLoader loader;
    private final boolean mockEnabled;

    public MockIntentSimulator(MockScenarioTemplateLoader loader,
                               @Value("${wikiagent.routing.mock-intents-enabled:true}") boolean mockEnabled) {
        this.loader = loader;
        this.mockEnabled = mockEnabled;
    }

    public boolean isMockEnabled() {
        return mockEnabled;
    }

    /**
     * 运行 Mock 意图模拟。
     *
     * @param intent    意图编码（ai_coding / customer_intake / business_rule_config / order_query）
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @param userInput 用户原始输入
     * @param sse       SSE 发送器
     */
    public void run(String intent, String userId, String sessionId,
                    String userInput, SseSender sse) {
        if (!mockEnabled || !loader.hasTemplate(intent)) {
            log.warn("Mock 意图 {} 未启用或模板未加载，降级为 knowledge_qa", intent);
            sse.send("error", Map.of("message", "Mock intent unavailable: " + intent));
            return;
        }

        JsonNode template = loader.getTemplate(intent);
        int seq = random.nextInt(10000);

        // 1. 分阶段推进 + SSE stage 事件
        JsonNode stages = template.get("stages");
        if (stages != null && stages.isArray()) {
            for (JsonNode stage : stages) {
                String stageId = stage.get("id").asText();
                String label = stage.get("label").asText();
                JsonNode delayRange = stage.get("delayMs");
                int delay = 500;
                if (delayRange != null && delayRange.isArray() && delayRange.size() >= 2) {
                    int min = delayRange.get(0).asInt();
                    int max = delayRange.get(1).asInt();
                    delay = min + random.nextInt(Math.max(1, max - min));
                }
                // 发送 stage 事件
                sse.send("stage", Map.of("stageId", stageId, "label", label));
                log.debug("Mock {} stage: {} ({}ms)", intent, stageId, delay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // 2. 填充占位数据 + 渲染响应模板
        String responseText = renderResponse(template, intent, seq, userInput);

        // 3. 发送最终 delta + done
        sse.send("delta", Map.of("text", responseText));
        sse.send("done", Map.of(
                "intent", intent,
                "mock", true,
                "userId", userId,
                "sessionId", sessionId
        ));
    }

    /**
     * 渲染响应模板：填充占位符。
     */
    private String renderResponse(JsonNode template, String intent, int seq, String userInput) {
        String responseTemplate = template.has("responseTemplate")
                ? template.get("responseTemplate").asText()
                : "Mock 响应: " + intent;

        // 简单占位符填充
        String result = responseTemplate
                .replace("{seq}", String.valueOf(seq))
                .replace("{YYYYMMDD}", LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")))
                .replace("{requirement_summary}", truncate(userInput, 50))
                .replace("{customer_name}", "张三")
                .replace("{amount}", String.valueOf(100 + random.nextInt(9900)))
                .replace("{effective_time}", LocalDate.now().plusDays(1).toString())
                .replace("{logistics_track}", "【上海转运中心】快件已发往目的地")
                .replace("{code_snippet}", generateMockCodeSnippet(intent))
                .replace("{category}", "CAT-" + random.nextInt(99));

        // 替换 {seq:NNd} 格式的占位符（用正则 + Matcher）
        java.util.regex.Pattern seqPattern = java.util.regex.Pattern.compile("\\{seq:(\\d+)d}");
        java.util.regex.Matcher seqMatcher = seqPattern.matcher(result);
        StringBuilder seqResult = new StringBuilder();
        while (seqMatcher.find()) {
            int digits = Integer.parseInt(seqMatcher.group(1));
            String replacement = String.format("%0" + digits + "d", seq);
            seqMatcher.appendReplacement(seqResult, replacement);
        }
        seqMatcher.appendTail(seqResult);
        result = seqResult.toString();

        // 替换 test_passed 随机值
        JsonNode dataPlaceholders = template.get("dataPlaceholders");
        if (dataPlaceholders != null) {
            JsonNode testPassed = dataPlaceholders.get("test_passed");
            if (testPassed != null && testPassed.has("values")) {
                JsonNode values = testPassed.get("values");
                int idx = random.nextInt(values.size());
                int testVal = values.get(idx).asInt();
                result = result.replace("{test_passed}", String.valueOf(testVal));
            }
            JsonNode status = dataPlaceholders.get("status");
            if (status != null && status.has("values")) {
                JsonNode values = status.get("values");
                int idx = random.nextInt(values.size());
                String statusVal = values.get(idx).asText();
                result = result.replace("{status}", statusVal);
            }
            JsonNode impactCount = dataPlaceholders.get("impact_count");
            if (impactCount != null && impactCount.has("values")) {
                JsonNode values = impactCount.get("values");
                int idx = random.nextInt(values.size());
                int impactVal = values.get(idx).asInt();
                result = result.replace("{impact_count}", String.valueOf(impactVal));
            }
        }

        return result;
    }

    private String generateMockCodeSnippet(String intent) {
        if ("ai_coding".equals(intent)) {
            return """
                    public class OrderService {
                        private final OrderRepository repo;
                        
                        public Order createOrder(OrderRequest req) {
                            Order order = new Order();
                            order.setId(UUID.randomUUID().toString());
                            order.setStatus("PENDING");
                            return repo.save(order);
                        }
                    }""";
        }
        return "// 代码生成中...";
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
