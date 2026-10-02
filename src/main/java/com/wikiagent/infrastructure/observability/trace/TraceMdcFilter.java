package com.wikiagent.infrastructure.observability.trace;

import com.wikiagent.domain.observability.trace.TraceIdGenerator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 子项目 I（AC-I1）HTTP 入口 traceId 过滤器（最高优先级，先于业务/限流过滤器）。
 * <ol>
 *   <li>读取 {@code X-Trace-Id} 请求头：有则透传，无则用 {@link TraceIdGenerator} 生成；</li>
 *   <li>{@code MDC.put("traceId", ...)}，使本次请求全部日志带 traceId，
 *       model_call_log.trace_id 同源（{@code ModelCallRecorder} 取 MDC）；</li>
 *   <li>响应回写同值 {@code X-Trace-Id} 头，供调用方/网关串联；</li>
 *   <li>finally 清除 MDC，防请求线程复用串链。</li>
 * </ol>
 * 开关：{@code wikiagent.observability.enabled}（缺省 true，默认安全开启）。
 * <p>
 * 配置样例（环境变量，无需改 application.yml）：
 * {@code WIKIAGENT_OBSERVABILITY_ENABLED=false} 可整体关闭可观测新组件。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "wikiagent.observability.enabled", havingValue = "true", matchIfMissing = true)
public class TraceMdcFilter extends OncePerRequestFilter {

    /** traceId 请求/响应头名。 */
    public static final String TRACE_HEADER = "X-Trace-Id";

    /** SLF4J MDC 键名（与 ModelCallRecorder 既有约定一致）。 */
    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = TraceIdGenerator.resolve(request.getHeader(TRACE_HEADER));
        MDC.put(MDC_KEY, traceId);
        // 在进入链路前回写：即使后续过滤器/控制器短路（如 429），响应仍携带 traceId。
        response.setHeader(TRACE_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
