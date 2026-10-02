package com.wikiagent.interfaces.task;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 10 开关关闭：wikiagent.task.enabled=false 时任务 API 一律 503。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "wikiagent.task.enabled=false"
})
class TaskApiDisabledIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void submitReturns503WhenDisabled() throws Exception {
        mvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskType\":\"INGEST\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void controlReturns503WhenDisabled() throws Exception {
        mvc.perform(post("/api/tasks/tsk_x/suspend"))
                .andExpect(status().isServiceUnavailable());
    }
}
