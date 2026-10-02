package com.wikiagent.infrastructure.observability.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wikiagent.config.TaskProperties;
import com.wikiagent.domain.observability.TaskMetricsQueryPort;
import com.wikiagent.infrastructure.trace.AuditLogRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

/**
 * 子项目 I（AC-I3）限流 + 队列背压过滤器（TraceMdcFilter 之后执行，429 响应也带 traceId）。
 *
 * <h3>用户/租户令牌桶</h3>
 * 按 {@code X-Tenant-Id（缺省 default）+ X-User-Id（缺省 anonymous）} 维度做最简内存令牌桶
 * （{@link SimpleTokenBucket}，容量/补充速率取 {@code wikiagent.ratelimit.requests-per-minute}，
 * 默认 60/min）。超限不进入控制器：429 JSON + Retry-After，并写 audit_log
 * （event_type=RATE_LIMITED，含 userId/path，复用既有审计 appender）。
 *
 * <h3>队列背压</h3>
 * {@code POST /api/tasks} 且 {@code wikiagent.task.tenant-max-concurrent>0} 时，
 * 经只读端口查该租户 RUNNING 数，已达上限返回 429（Retry-After，语义为过载 shed/背压）。
 * 该判定放在过滤器层是为了不改任务提交服务（AC-I 所有权约束）。
 *
 * <h3>边界声明</h3>
 * <ul>
 *   <li>仅拦截 /api/**；actuator 抓取与静态资源不占用户令牌；</li>
 *   <li>当前为<b>单机</b>限流；多实例需在网关层做全局限流或后续提供 Redis 令牌桶
 *       （ObjectProvider 留 RedisTemplate 扩展位），Redis 不可用时自动退回本单机实现；</li>
 *   <li>租户 ID 当前取 X-Tenant-Id 头（生产由网关注入，与 X-User-Id 同一信任边界）。</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnProperty(name = "wikiagent.ratelimit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    public static final String USER_HEADER = "X-User-Id";
    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String DEFAULT_USER = "anonymous";
    public static final String DEFAULT_TENANT = "default";

    private final RateLimitProperties properties;
    private final ObjectProvider<AuditLogRepository> auditProvider;
    private final ObjectProvider<TaskMetricsQueryPort> taskQueryProvider;
    private final ObjectProvider<TaskProperties> taskPropertiesProvider;
    private final ObjectMapper objectMapper;
    private final SimpleTokenBucket buckets;

    public RateLimitFilter(RateLimitProperties properties,
                           ObjectProvider<AuditLogRepository> auditProvider,
                           ObjectProvider<TaskMetricsQueryPort> taskQueryProvider,
                           ObjectProvider<TaskProperties> taskPropertiesProvider,
                           ObjectMapper objectMapper) {
        this.properties = properties;
        this.auditProvider = auditProvider;
        this.taskQueryProvider = taskQueryProvider;
        this.taskPropertiesProvider = taskPropertiesProvider;
        this.objectMapper = objectMapper;
        this.buckets = new SimpleTokenBucket(Math.max(1, properties.getRequestsPerMinute()));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 仅 /api/** 占用用户令牌；actuator/静态资源放行（Prometheus 抓取不受限）
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String userId = headerOrDefault(request, USER_HEADER, DEFAULT_USER);
        String tenantId = headerOrDefault(request, TENANT_HEADER, DEFAULT_TENANT);

        // ① 队列背压（提交点前置）：租户 RUNNING 已达上限直接 shed
        if ("POST".equalsIgnoreCase(request.getMethod())
                && "/api/tasks".equals(request.getRequestURI())) {
            Integer retryAfter = backpressureRetryAfter(tenantId);
            if (retryAfter != null) {
                reject(request, response, userId, tenantId, retryAfter, "TASK_BACKPRESSURE");
                return;
            }
        }

        // ② 用户+租户令牌桶
        buckets.sweepIfNeeded();
        SimpleTokenBucket.Decision decision = buckets.tryAcquire(tenantId + ":" + userId);
        if (!decision.allowed()) {
            reject(request, response, userId, tenantId, decision.retryAfterSec(), "RATE_LIMITED");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /** 背压判定：返回非 null Retry-After 表示需要拒绝；未配置上限/查询不可用放行。 */
    private Integer backpressureRetryAfter(String tenantId) {
        try {
            TaskProperties taskProps = taskPropertiesProvider.getIfAvailable();
            TaskMetricsQueryPort query = taskQueryProvider.getIfAvailable();
            if (taskProps == null || query == null
                    || taskProps.getTenantMaxConcurrent() <= 0) {
                return null;
            }
            long running = query.countRunningByTenant(tenantId);
            if (running >= taskProps.getTenantMaxConcurrent()) {
                return Math.max(1, properties.getBackpressureRetryAfterSec());
            }
        } catch (Exception e) {
            // 背压探测失败采取 fail-open（绝不让观测组件阻断业务提交）
            log.warn("队列背压探测失败（放行）tenant={}: {}", tenantId, e.getMessage());
        }
        return null;
    }

    /** 写 429 响应 + 审计。 */
    private void reject(HttpServletRequest request, HttpServletResponse response,
                        String userId, String tenantId, int retryAfterSec, String reason)
            throws IOException {
        writeAudit(request, userId, tenantId, retryAfterSec, reason);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
        response.setHeader("Retry-After", String.valueOf(retryAfterSec));
        ObjectNode body = objectMapper.createObjectNode();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.TOO_MANY_REQUESTS.value());
        body.put("error", "TOO_MANY_REQUESTS");
        body.put("message", "请求过于频繁");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    /** 复用既有 audit_log appender（失败不影响拒绝响应）。 */
    private void writeAudit(HttpServletRequest request, String userId, String tenantId,
                            int retryAfterSec, String reason) {
        try {
            AuditLogRepository audit = auditProvider.getIfAvailable();
            if (audit == null) {
                return;
            }
            String path = request.getMethod() + " " + request.getRequestURI();
            ObjectNode detail = objectMapper.createObjectNode()
                    .put("reason", reason)
                    .put("tenantId", tenantId)
                    .put("retryAfterSec", retryAfterSec)
                    .put("path", path);
            audit.log(userId, null, "RATE_LIMITED", "rate_limit", "WARN",
                    path, "REJECTED", objectMapper.writeValueAsString(detail));
        } catch (Exception e) {
            log.warn("限流审计写入失败（不影响拒绝响应）: {}", e.getMessage());
        }
    }

    private static String headerOrDefault(HttpServletRequest request, String name, String fallback) {
        String v = request.getHeader(name);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    /** 测试辅助：令牌桶当前令牌数。 */
    double availableTokens(String key) {
        return buckets.availableTokens(key);
    }
}
