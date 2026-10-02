package com.wikiagent.infrastructure.security;

import com.wikiagent.config.TrustBoundaryProperties;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 子项目 J（AC-J4）：网关 ↔ 应用信任边界过滤器。
 * <p>
 * 威胁模型：应用部署在 API 网关之后，{@code X-User-Id} / {@code X-Business-Identity}
 * 等身份头只能由网关注入；若客户端可绕过网关直连应用，就能伪造任意身份。
 * 本过滤器在 {@code wikiagent.security.trust-boundary.enabled=true}（prod/staging 默认）时生效：
 * <ol>
 *   <li>请求<b>未携带任何受保护身份头</b>：视为匿名访问，正常放行
 *       （健康检查/公开接口不受影响，不强制所有请求带密钥）；</li>
 *   <li>请求携带了身份头：必须同时携带正确的 {@code X-Internal-Secret} 共享密钥，
 *       缺失/错误一律 401，身份不会进入任何下游组件；</li>
 *   <li>fail-fast：过滤器启用但 {@code wikiagent.security.trust-boundary.secret} 为空时，
 *       应用启动直接失败——密钥只能由环境变量 {@code WIKIAGENT_INTERNAL_SECRET} 注入，
 *       代码/配置文件均无硬编码默认值。</li>
 * </ol>
 * 密钥比较使用恒定时间摘要比较，降低计时侧信道风险；401 响应不回显任何密钥信息。
 */
@Component
@ConditionalOnProperty(prefix = "wikiagent.security.trust-boundary",
        name = "enabled", havingValue = "true", matchIfMissing = false)
public class TrustedHeaderFilter extends OncePerRequestFilter {

    /** 网关 → 应用共享密钥请求头。 */
    public static final String INTERNAL_SECRET_HEADER = "X-Internal-Secret";

    private static final Logger log = LoggerFactory.getLogger(TrustedHeaderFilter.class);

    private final TrustBoundaryProperties properties;
    private final List<String> identityHeaders;

    public TrustedHeaderFilter(TrustBoundaryProperties properties) {
        this.properties = properties;
        this.identityHeaders = List.copyOf(properties.getIdentityHeaders());
    }

    @PostConstruct
    void validateSecret() {
        if (!StringUtils.hasText(properties.getSecret())) {
            throw new IllegalStateException("""
                    信任边界已启用（wikiagent.security.trust-boundary.enabled=true），\
                    但共享密钥 wikiagent.security.trust-boundary.secret 为空。\
                    必须通过环境变量 WIKIAGENT_INTERNAL_SECRET 注入非空密钥后再启动；\
                    代码与配置文件不提供默认密钥。""");
        }
        if (identityHeaders.isEmpty()) {
            throw new IllegalStateException(
                    "wikiagent.security.trust-boundary.identity-headers 不能为空，"
                            + "否则信任边界没有需要保护的身份头（如非有意，请删除该覆盖配置）");
        }
        log.info("信任边界过滤器已启用，保护身份头 {}（密钥来自环境变量，不记录其值）", identityHeaders);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        boolean identityHeaderPresent = false;
        for (String header : identityHeaders) {
            if (StringUtils.hasText(request.getHeader(header))) {
                identityHeaderPresent = true;
                break;
            }
        }

        // 匿名请求（无任何身份头）放行：公开接口与 /actuator/health 等无需密钥
        if (!identityHeaderPresent) {
            filterChain.doFilter(request, response);
            return;
        }

        String provided = request.getHeader(INTERNAL_SECRET_HEADER);
        if (provided == null || !constantTimeEquals(properties.getSecret(), provided)) {
            log.warn("拒绝携带身份头但共享密钥缺失/不匹配的请求: {} {} remote={}",
                    request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
            writeUnauthorized(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        try {
            // MessageDigest.isEqual 自 Java 6u17 起为恒定时间比较
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    actual.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("""
                {"error":"untrusted_identity_headers","message":"\
                身份头只能由受信网关注入；直连请求携带 X-User-Id/X-Business-Identity \
                必须提供正确的 X-Internal-Secret"}""");
    }
}
