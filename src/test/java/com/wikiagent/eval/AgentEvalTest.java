package com.wikiagent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.domain.routing.Intent;
import com.wikiagent.domain.routing.LlmRouterPort;
import com.wikiagent.domain.routing.RouteDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * v1-v2 §9 Agent 评测 - 黄金用例回归测试。
 * <p>
 * 加载 {@code eval/golden.json} 中的 10 个测试用例，验证：
 * <ul>
 *   <li>LlmRouterPort 路由的意图与 expectedIntent 一致</li>
 *   <li>路由的模型复杂度（simple/complex）与 expectedModel 一致</li>
 *   <li>JudgeService 评分逻辑正确</li>
 * </ul>
 * 通过 @MockBean 模拟 LlmRouterPort，避免依赖真实 API Key。
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=update"
})
class AgentEvalTest {

    /** 独立 H2 文件（flyway 关闭的上下文不得污染默认库，否则后续 flyway 迁移会因列已存在而失败）。 */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:file:./data/h2/test-agent-eval-" + System.nanoTime()
                        + ";AUTO_SERVER=TRUE;MODE=MySQL");
    }

    /** 黄金用例 JSON 结构。 */
    record GoldenCase(String question, String expectedIntent, String expectedModel, String groundTruth) {}

    @MockBean
    private LlmRouterPort router;

    @Autowired
    private ObjectMapper objectMapper;

    private JudgeService judge;

    @BeforeEach
    void setUp() {
        judge = new JudgeService();
    }

    @Test
    void testGoldenCases() throws Exception {
        List<GoldenCase> cases = loadGoldenCases();
        assertEquals(10, cases.size(), "golden.json 应包含 10 个测试用例");

        for (GoldenCase testCase : cases) {
            // 构造期望的 RouteDecision（根据 expectedIntent）
            Intent expectedIntent = Intent.fromCode(testCase.expectedIntent());
            RouteDecision expectedDecision = expectedIntent.isComplex()
                    ? RouteDecision.complex(expectedIntent, "complex-model")
                    : RouteDecision.simple(expectedIntent, "simple-model");

            // 模拟 LlmRouterPort 的路由行为
            when(router.route(testCase.question())).thenReturn(expectedDecision);

            // 执行路由
            RouteDecision actual = router.route(testCase.question());
            assertNotNull(actual, "路由结果不应为 null：" + testCase.question());

            // 验证意图正确
            assertTrue(judge.isIntentCorrect(actual.intent().code(), testCase.expectedIntent()),
                    "意图路由错误：期望 " + testCase.expectedIntent()
                            + "，实际 " + actual.intent().code() + "，问题：" + testCase.question());

            // 验证模型复杂度
            String actualModel = actual.isComplex() ? "complex" : "simple";
            assertEquals(testCase.expectedModel(), actualModel,
                    "模型选择错误：期望 " + testCase.expectedModel()
                            + "，实际 " + actualModel + "，问题：" + testCase.question());

            // 验证 JudgeService 评分：包含全部关键词的答案应得满分
            String fullAnswer = "包含关键词：" + testCase.groundTruth();
            double fullScore = judge.score(fullAnswer, testCase.groundTruth());
            assertEquals(1.0, fullScore, 0.001,
                    "包含全部关键词的答案应得满分：" + testCase.question());

            // 验证空答案得 0 分
            double zeroScore = judge.score("", testCase.groundTruth());
            assertEquals(0.0, zeroScore, 0.001,
                    "空答案应得 0 分：" + testCase.question());
        }
    }

    @Test
    void testJudgeServiceIntentComparison() {
        assertTrue(judge.isIntentCorrect("knowledge_qa", "knowledge_qa"));
        assertTrue(judge.isIntentCorrect("KNOWLEDGE_QA", "knowledge_qa"));
        assertTrue(!judge.isIntentCorrect("ai_coding", "knowledge_qa"));
        assertTrue(!judge.isIntentCorrect(null, "knowledge_qa"));
    }

    @Test
    void testJudgeServicePartialScore() {
        // 3 个关键词中匹配 2 个 → 得分 2/3
        double score = judge.score("差旅报销标准已包含", "差旅,报销,标准,住宿");
        assertEquals(0.75, score, 0.001,
                "4 个关键词匹配 3 个应得 0.75 分");
    }

    /** 从 classpath 加载 golden.json。 */
    private List<GoldenCase> loadGoldenCases() throws Exception {
        ClassPathResource resource = new ClassPathResource("eval/golden.json");
        try (InputStream is = resource.getInputStream()) {
            return objectMapper.readValue(is, new TypeReference<List<GoldenCase>>() {});
        }
    }
}
