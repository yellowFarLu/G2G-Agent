package com.wikiagent.dto;

/**
 * 对话请求体。
 * <p>
 * v6 §21 新增 3 个可选字段用于 Multi-Agent 路由（{@code domain} / {@code subDomain} / {@code identity}），
 * 缺省 null 时由 {@code ChatService} 走 §2 单 Agent RAG 管道（向后兼容）。
 * <p>
 * 字段语义（§7.4 9×6×5 垂直隔离）：
 * <ul>
 *   <li>{@code domain} — 9 领域之一（industry_solution / merchant_center / pms / service_provider /
 *       trunk_line / customs / settlement / first_mile / trajectory）</li>
 *   <li>{@code subDomain} — 6 子领域之一（product_doc / operation_manual / faq /
 *       case_library / rule_config / api_doc）</li>
 *   <li>{@code identity} — 5 身份之一（admin / business / product / technology / test）</li>
 *   <li>{@code sessionId} — 业务会话 ID（实施校正 2026-09-22）：前端显式传入并展示，
 *       贯穿 trace 链路/审计/反馈/短期记忆；缺省时后端生成唯一 ID 并通过 SSE session 事件回传。</li>
 * </ul>
 */
public record ChatRequest(String question, String domain, String subDomain, String identity,
                          String sessionId) {

    /** 不含 sessionId 的请求（后端自动生成并回传）。 */
    public ChatRequest(String question, String domain, String subDomain, String identity) {
        this(question, domain, subDomain, identity, null);
    }

    /** 向后兼容：仅 question 字段（domain/subDomain/identity 缺省 null）。 */
    public ChatRequest(String question) {
        this(question, null, null, null, null);
    }
}
