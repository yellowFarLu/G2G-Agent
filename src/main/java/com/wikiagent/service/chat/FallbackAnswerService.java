package com.wikiagent.service.chat;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 知识库未命中兜底回答（实施校正 2026-09-22）。
 * <p>
 * 当检索结果为空（知识库中没有任何相关文档）时，按以下顺序兜底，
 * 避免一律以"知识库未命中"拒答：
 * <ol>
 *   <li><b>联网搜索</b>（{@code wikiagent.fallback.web-search-enabled=true}，默认开启）：
 *       经 DashScope API 实测，qwen-plus 等模型支持 {@code enable_search} 参数
 *       （SDK：{@link DashScopeChatOptions#enableSearch}），模型自动判断是否需要联网，
 *       适合时效性问题；实测当前 API 仅返回答案文本、不返回结构化来源链接，
 *       因此由提示词要求模型在文末注明来源站点。</li>
 *   <li><b>模型自身通用知识</b>（{@code wikiagent.fallback.own-knowledge-enabled=true}，默认开启）：
 *       联网关闭或同步建链失败时使用。</li>
 *   <li>两者均关闭：保留原 {@link PromptComposer#NO_CONTEXT} 拒答文案。</li>
 * </ol>
 * 防幻觉硬约束：两种兜底回答都必须显式声明"内容不来自企业知识库"，不得伪装为文档结论；
 * 全程通过 SSE 推送 {@code stage=fallback} 事件（mode=web_search/model_knowledge）告知前端来源属性。
 * 边界：联网生成发生在流式订阅期，若 DashScope 在该阶段报错，仍由 {@link ChatStreamer}
 * 统一发送 error 事件（与正常 RAG 生成失败的行为一致），不再二次降级。
 */
@Service
public class FallbackAnswerService {

    private static final Logger log = LoggerFactory.getLogger(FallbackAnswerService.class);

    /** 联网搜索模式系统提示。 */
    static final String WEB_SEARCH_SYSTEM = """
            你是企业知识库助手。用户的问题在企业知识库中没有检索到相关文档，已为你开启联网搜索，
            请基于搜索到的互联网最新信息回答，并严格遵守：
            1. 优先采用搜索结果中的事实与数据，并注明关键信息的时间点，避免用过时信息误导用户。
            2. 在回答末尾另起一行，以"参考来源："列出你实际引用的网站名称（能给出网址时附网址）。
            3. 搜索结果不足以确定的内容必须明确说明"无法从公开信息确认"，不要编造数据、政策或链接。
            4. 开头先用一句话声明："企业知识库未命中，以下回答来自联网搜索的公开信息。"
            5. 用简体中文回答，条理清晰、简明扼要。
            """;

    /** 模型自身通用知识模式系统提示。 */
    static final String OWN_KNOWLEDGE_SYSTEM = """
            你是企业知识库助手。用户的问题在企业知识库中没有检索到相关文档，
            将由你基于自身的通用知识回答，必须严格遵守：
            1. 回答开头先用一句话声明："企业知识库未命中，以下内容来自模型通用知识，并非企业文档，可能存在过时或不准确之处。"
            2. 只回答你有把握的通用知识；对于可能过时、你不确定或超出知识截止时间的内容，
               明确标注不确定性，不要编造具体数据、政策条文、链接或企业内部信息。
            3. 如果问题询问的是该企业的内部制度、流程、数据或系统，明确说明知识库中没有相关文档，
               建议用户上传文档或联系管理员，不要猜测企业内部情况。
            4. 用简体中文回答，条理清晰、简明扼要。
            """;

    /** 流式输出为空（如未配置 API Key 时的 NoOpChatModel）时给用户的提示。 */
    static final String EMPTY_NOTE =
            "知识库中未检索到相关内容，且当前模型服务未返回兜底回答（可能未配置 DASHSCOPE_API_KEY）。"
                    + "请上传相关文档或检查模型配置后重试。";

    private final ChatStreamer streamer;
    private final boolean webSearchEnabled;
    private final boolean ownKnowledgeEnabled;

    public FallbackAnswerService(ChatStreamer streamer,
                                 @Value("${wikiagent.fallback.web-search-enabled:true}") boolean webSearchEnabled,
                                 @Value("${wikiagent.fallback.own-knowledge-enabled:true}") boolean ownKnowledgeEnabled) {
        this.streamer = streamer;
        this.webSearchEnabled = webSearchEnabled;
        this.ownKnowledgeEnabled = ownKnowledgeEnabled;
    }

    /**
     * 知识库未命中时的兜底回答入口。方法自行负责发送 done/complete 或交由 streamer 收尾。
     *
     * @param question 用户原始问题
     * @param sse      SSE 发送器
     */
    public void answer(String question, SseSender sse) {
        // 第一级：联网搜索（enable_search 由模型自行判断是否真的发起检索）
        if (webSearchEnabled) {
            sse.send("stage", Map.of("stage", "fallback", "mode", "web_search"));
            try {
                Prompt prompt = new Prompt(
                        List.of(new SystemMessage(WEB_SEARCH_SYSTEM), new UserMessage(question)),
                        DashScopeChatOptions.builder().enableSearch(true).build());
                log.debug("知识库未命中，启用联网搜索兜底");
                streamer.stream(prompt, sse, EMPTY_NOTE);
                return;
            } catch (Exception e) {
                // 仅能捕获订阅建立前的同步异常；流式期错误由 ChatStreamer 发 error 事件
                log.warn("联网兜底建链失败，降级为模型自身知识: {}", e.getMessage());
            }
        }

        // 第二级：模型自身通用知识
        if (ownKnowledgeEnabled) {
            sse.send("stage", Map.of("stage", "fallback", "mode", "model_knowledge"));
            Prompt prompt = new Prompt(
                    List.of(new SystemMessage(OWN_KNOWLEDGE_SYSTEM), new UserMessage(question)));
            log.debug("知识库未命中，启用模型自身知识兜底");
            streamer.stream(prompt, sse, EMPTY_NOTE);
            return;
        }

        // 两级兜底均关闭：保留原拒答行为
        sse.send("delta", Map.of("text", PromptComposer.NO_CONTEXT));
        sse.send("done", Map.of());
        sse.complete();
    }
}
