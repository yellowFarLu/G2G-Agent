package com.wikiagent.eval;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实 LLM 烟雾测试（唯一触达真实模型的评测）。
 * <p>
 * 标记 {@code @Tag("LiveLLM")}：默认构建/{@code mvn test -Peval} 均通过 surefire
 * {@code excludedGroups=LiveLLM} 排除；仅夜间/发布前由人工显式运行：
 * <pre>
 * mvn test -Peval -Dtest=LiveLlmSmokeTest -Dsurefire.excludedGroups=
 * </pre>
 * 运行前提：环境已注入真实 DASHSCOPE_API_KEY；无 key 时测试自动跳过（abort），
 * 绝不把离线 CI 演变成对外部服务的硬依赖。
 */
@Tag("LiveLLM")
@SpringBootTest
class LiveLlmSmokeTest {

    @Autowired
    private ObjectProvider<ChatModel> chatModelProvider;

    @Test
    void 真实大模型单轮对话可达() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getProperty("DASHSCOPE_API_KEY");
        }
        assumeTrue(apiKey != null && !apiKey.isBlank(),
                "未配置 DASHSCOPE_API_KEY，跳过真实 LLM 烟雾测试");
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        assumeTrue(chatModel != null, "ChatModel Bean 未装配，跳过真实 LLM 烟雾测试");

        String reply = chatModel.call("离线评测真实链路烟雾测试，请只回复两个字：正常");

        assertThat(reply).isNotBlank();
    }
}
