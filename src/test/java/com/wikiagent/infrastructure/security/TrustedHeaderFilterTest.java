package com.wikiagent.infrastructure.security;

import com.wikiagent.config.TrustBoundaryProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-J4 {@link TrustedHeaderFilter} 单测（standaloneSetup MockMvc，无 Spring 全上下文/无 Docker）：
 * fail-fast、伪造身份头 401、密钥正确放行身份头、匿名请求放行。
 */
class TrustedHeaderFilterTest {

    private static final String SECRET = "s3cret-shared-between-gateway-and-app";

    /** 回显身份头的最小控制器，断言过滤器放行后下游确实收到身份头。 */
    @Controller
    static class EchoController {
        @GetMapping("/ping")
        @ResponseBody
        String ping(HttpServletRequest request,
                    @RequestHeader(value = "X-User-Id", required = false) String userId,
                    @RequestHeader(value = "X-Business-Identity", required = false) String identity) {
            return "uid=" + userId + " identity=" + identity;
        }
    }

    private TrustBoundaryProperties properties;

    @BeforeEach
    void setUp() {
        properties = new TrustBoundaryProperties();
        properties.setEnabled(true);
        properties.setSecret(SECRET);
        properties.setIdentityHeaders(List.of("X-User-Id", "X-Business-Identity"));
    }

    private MockMvc mvc(TrustedHeaderFilter filter) {
        return MockMvcBuilders.standaloneSetup(new EchoController())
                .addFilters(filter)
                .build();
    }

    @Test
    void 启用但密钥为空时启动失败() {
        properties.setSecret("");
        TrustedHeaderFilter filter = new TrustedHeaderFilter(properties);
        assertThatThrownBy(filter::validateSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WIKIAGENT_INTERNAL_SECRET");
    }

    @Test
    void 启用但密钥为空白时启动失败() {
        properties.setSecret("   ");
        assertThatThrownBy(() -> new TrustedHeaderFilter(properties).validateSecret())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 身份头列表为空时启动失败() {
        properties.setIdentityHeaders(List.of());
        assertThatThrownBy(() -> new TrustedHeaderFilter(properties).validateSecret())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identity-headers");
    }

    @Test
    void 伪造身份头且密钥错误返回401() throws Exception {
        MockMvc mvc = mvc(new TrustedHeaderFilter(properties));

        mvc.perform(get("/ping")
                        .header("X-User-Id", "attacker-admin")
                        .header(TrustedHeaderFilter.INTERNAL_SECRET_HEADER, "wrong-secret"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 携带身份头但无密钥头返回401() throws Exception {
        MockMvc mvc = mvc(new TrustedHeaderFilter(properties));

        mvc.perform(get("/ping").header("X-User-Id", "attacker-admin"))
                .andExpect(status().isUnauthorized())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain(SECRET));
    }

    @Test
    void 伪造business身份无密钥返回401() throws Exception {
        MockMvc mvc = mvc(new TrustedHeaderFilter(properties));

        mvc.perform(get("/ping")
                        .header("X-Business-Identity", "admin")
                        .header(TrustedHeaderFilter.INTERNAL_SECRET_HEADER, "guess"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 密钥正确时身份头透传下游并200() throws Exception {
        MockMvc mvc = mvc(new TrustedHeaderFilter(properties));

        mvc.perform(get("/ping")
                        .header("X-User-Id", "u-123")
                        .header("X-Business-Identity", "business")
                        .header(TrustedHeaderFilter.INTERNAL_SECRET_HEADER, SECRET))
                .andExpect(status().isOk())
                .andExpect(content().string("uid=u-123 identity=business"));
    }

    @Test
    void 匿名请求无身份头无密钥也放行() throws Exception {
        MockMvc mvc = mvc(new TrustedHeaderFilter(properties));

        mvc.perform(get("/ping"))
                .andExpect(status().isOk())
                .andExpect(content().string("uid=null identity=null"));
    }
}
