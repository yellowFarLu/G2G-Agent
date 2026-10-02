package com.wikiagent.infrastructure.tool;

import com.wikiagent.service.retrieve.RetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * v1-v2 §2 工具：知识库检索。
 * <p>
 * 封装既有 {@link RetrievalService}，对 Agent 暴露单一 execute 入口。
 * 将检索到的来源与上下文格式化为文本，供节点执行结果回写交接清单。
 * <p>
 * v1-v2 与 v6 PERO 双路径共用（v6 经 {@code PeroToolExecutor} 分派调用）。
 */
@Component
public class SearchKnowledgeBaseTool {

    private static final Logger log = LoggerFactory.getLogger(SearchKnowledgeBaseTool.class);

    /** 工具名（与 ToolRegistry / DefaultToolRegistry 白名单一致）。 */
    public static final String NAME = "search_knowledge_base";

    private final RetrievalService retrieval;

    public SearchKnowledgeBaseTool(RetrievalService retrieval) {
        this.retrieval = retrieval;
    }

    /**
     * 检索知识库并返回格式化文本。
     *
     * @param query 检索查询
     * @return 来源列表 + 上下文文本；无结果时返回提示
     */
    public String execute(String query) {
        if (query == null || query.isBlank()) {
            return "[search_knowledge_base] 查询为空，未执行检索";
        }
        try {
            RetrievalService.RetrievalResult result = retrieval.retrieve(query);
            if (result.context() == null || result.context().isBlank()) {
                return "[search_knowledge_base] 知识库中未检索到与该查询相关的内容";
            }
            return formatResult(result.sources(), result.context());
        } catch (Exception e) {
            log.warn("知识库检索失败 query={} err={}", query, e.getMessage());
            return "[search_knowledge_base] 检索失败: " + e.getMessage();
        }
    }

    /** 将来源列表与上下文拼装为单段文本。 */
    private String formatResult(List<RetrievalService.Source> sources, String context) {
        StringBuilder sb = new StringBuilder();
        sb.append("来源：\n");
        if (sources.isEmpty()) {
            sb.append("（无）\n");
        } else {
            for (RetrievalService.Source s : sources) {
                sb.append("[").append(s.index()).append("] ")
                        .append(s.filename()).append(" (score=").append(s.score()).append(")\n");
            }
        }
        sb.append("\n参考资料：\n").append(context);
        return sb.toString();
    }
}
