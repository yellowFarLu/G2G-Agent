package com.wikiagent.infrastructure.observability.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-I3 限流 IT：用户+租户令牌桶突发 429、Retry-After、audit_log RATE_LIMITED 落证，
 * actuator 路径不占用户令牌，用户间互不影响。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "wikiagent.ratelimit.requests-per-minute=10"
})
class RateLimitFilterIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:file:./data/h2/test-rate-limit-" + System.nanoTime()
                        + ";AUTO_SERVER=TRUE;MODE=MySQL;LOCK_TIMEOUT=10000");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void 突发十五个请求恰五个429并写审计() throws Exception {
        int tooMany = 0;
        for (int i = 0; i < 15; i++) {
            MvcResult result = mvc.perform(get("/api/tasks")
                            .header("X-User-Id", "rl-burst-user")
                            .header("X-Tenant-Id", "rl-tenant"))
                    .andReturn();
            if (result.getResponse().getStatus() == 429) {
                tooMany++;
                assertThat(result.getResponse().getHeader("Retry-After")).isNotNull();
                String body = result.getResponse().getContentAsString();
                assertThat(body).contains("TOO_MANY_REQUESTS").contains("请求过于频繁");
            }
        }
        assertThat(tooMany).isEqualTo(5);

        Integer auditRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE event_type = 'RATE_LIMITED' AND user_id = 'rl-burst-user'",
                Integer.class);
        assertThat(auditRows).isEqualTo(5);
        String detail = jdbc.queryForObject(
                "SELECT detail FROM audit_log WHERE event_type = 'RATE_LIMITED' AND user_id = 'rl-burst-user' "
                        + "LIMIT 1",
                String.class);
        assertThat(detail).contains("\"tenantId\":\"rl-tenant\"").contains("GET /api/tasks");
    }

    @Test
    void 令牌桶按用户隔离且actuator路径不消耗令牌() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(get("/api/tasks").header("X-User-Id", "rl-user-a"));
        }
        // A 已耗尽；B 独立桶不受影响
        mvc.perform(get("/api/tasks").header("X-User-Id", "rl-user-b"))
                .andExpect(status().isOk());

        // actuator 不受限流：连续抓取均 200
        for (int i = 0; i < 70; i++) {
            mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
        }
    }
}
