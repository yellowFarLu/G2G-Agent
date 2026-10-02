package com.wikiagent.infrastructure.observability.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AC-I3 队列背压 IT：wikiagent.task.tenant-max-concurrent=2，租户存在 2 条 RUNNING 时
 * POST /api/tasks 被 429 + Retry-After 拦截并写 TASK_BACKPRESSURE 审计；其他租户不受影响。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "wikiagent.ratelimit.requests-per-minute=100000",
        "wikiagent.task.tenant-max-concurrent=2"
})
class TaskBackpressureFilterIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:file:./data/h2/test-task-backpressure-" + System.nanoTime()
                        + ";AUTO_SERVER=TRUE;MODE=MySQL;LOCK_TIMEOUT=10000");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedRunningTasks() {
        for (int i = 0; i < 2; i++) {
            String id = UUID.randomUUID().toString().substring(0, 12);
            jdbc.update("INSERT INTO task_instance "
                            + "(task_id, task_type, biz_key, payload, status, attempt, max_attempts, "
                            + "submitted_by, tenant_id, next_run_at, created_at, updated_at) "
                            + "VALUES (?, 'AGENT', ?, '{}', 'RUNNING', 0, 3, 'bp-tester', 'bp-full-tenant', "
                            + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    "task-" + id, "biz-" + id);
        }
    }

    @Test
    void 达租户并发上限时提交被429背压并写审计() throws Exception {
        MvcResult result = mvc.perform(post("/api/tasks")
                        .header("X-Tenant-Id", "bp-full-tenant")
                        .header("X-User-Id", "bp-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskType\":\"AGENT\",\"bizKey\":\"bp-blocked-1\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(429);
        assertThat(result.getResponse().getHeader("Retry-After")).isEqualTo("5");
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("TOO_MANY_REQUESTS");

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE event_type = 'RATE_LIMITED' AND user_id = 'bp-user' "
                        + "AND detail LIKE '%TASK_BACKPRESSURE%'",
                Integer.class);
        assertThat(rows).isEqualTo(1);

        // 同租户再次提交仍然被背压
        assertThat(mvc.perform(post("/api/tasks")
                        .header("X-Tenant-Id", "bp-full-tenant")
                        .header("X-User-Id", "bp-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskType\":\"AGENT\",\"bizKey\":\"bp-blocked-2\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void 未达上限的租户提交不受背压影响() throws Exception {
        MvcResult result = mvc.perform(post("/api/tasks")
                        .header("X-Tenant-Id", "bp-empty-tenant")
                        .header("X-User-Id", "bp-user-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskType\":\"AGENT\",\"bizKey\":\"bp-pass-"
                                + UUID.randomUUID().toString().substring(0, 8) + "\"}"))
                .andReturn();
        // 关键断言：不是 429（过滤器放行；控制器层面 2xx/4xx 均与背压无关）
        assertThat(result.getResponse().getStatus()).isNotEqualTo(429);
    }
}
