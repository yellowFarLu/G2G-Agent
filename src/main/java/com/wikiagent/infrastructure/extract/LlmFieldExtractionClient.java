package com.wikiagent.infrastructure.extract;

import com.wikiagent.application.llm.ModelCallRecorder;
import com.wikiagent.domain.llm.ModelCallLogPurpose;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 字段提取 LLM 客户端：复用 complexChatModel（复杂任务模型）。
 * 仅负责"提示词 → 模型文本"；JSON 解析/校验/修复重试由应用层编排。
 * <p>
 * E4：每次调用落 model_call_log（purpose=EXTRACT），打点失败不影响提取。
 */
@Component
public class LlmFieldExtractionClient {

    private final ChatModel chatModel;
    private final ModelCallRecorder recorder;
    private final String modelName;

    /** 兼容旧构造（测试子类 super(null) 场景）：无打点。 */
    public LlmFieldExtractionClient(@Qualifier("complexChatModel") ChatModel chatModel) {
        this(chatModel, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LlmFieldExtractionClient(@Qualifier("complexChatModel") ChatModel chatModel,
                                    ObjectProvider<ModelCallRecorder> recorder,
                                    @Value("${wikiagent.routing.complex-model:qwen-max}") String modelName) {
        this.chatModel = chatModel;
        this.recorder = recorder == null ? null : recorder.getIfAvailable();
        this.modelName = modelName;
    }

    /**
     * @param systemPrompt 字段规格与输出格式约束
     * @param documentText 带页标记的文档文本
     * @param repairFeedback 首轮校验错误（null=首轮；非空=修复轮，要求模型据此修正）
     */
    public String call(String systemPrompt, String documentText, String repairFeedback) {
        StringBuilder user = new StringBuilder("文档内容如下：\n").append(documentText);
        if (repairFeedback != null && !repairFeedback.isBlank()) {
            user.append("\n\n上一轮结果未通过校验，请只输出修正后的 JSON，错误如下：\n")
                    .append(repairFeedback);
        }
        long started = System.currentTimeMillis();
        try {
            ChatResponse response = chatModel.call(new Prompt(
                    List.of(new SystemMessage(systemPrompt), new UserMessage(user.toString()))));
            long latency = System.currentTimeMillis() - started;
            record(usageOf(response, true), usageOf(response, false), latency, true);
            String text = response.getResult().getOutput().getText();
            return text == null ? "" : text.trim();
        } catch (Exception e) {
            record(null, null, System.currentTimeMillis() - started, false);
            throw new IllegalStateException("字段提取模型调用失败: " + e.getMessage(), e);
        }
    }

    private void record(Integer in, Integer out, long latency, boolean ok) {
        if (recorder != null) {
            recorder.record(ModelCallLogPurpose.EXTRACT, "dashscope", modelName,
                    in, out, latency, ok, null, null, null);
        }
    }

    private static Integer usageOf(ChatResponse resp, boolean prompt) {
        try {
            if (resp == null || resp.getMetadata() == null) {
                return null;
            }
            Usage u = resp.getMetadata().getUsage();
            if (u == null) {
                return null;
            }
            return prompt ? u.getPromptTokens() : u.getCompletionTokens();
        } catch (Exception e) {
            return null;
        }
    }
}
