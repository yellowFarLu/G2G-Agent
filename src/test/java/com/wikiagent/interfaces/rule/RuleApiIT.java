package com.wikiagent.interfaces.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-D4 MockMvc 集成测试：规则 CRUD/发布/重算/案件列表/处置端点全通。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
class RuleApiIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Test
    void ruleCrudPublishComputeCaseResolveAllPass() throws Exception {
        String code = "api-" + System.nanoTime();
        String dsl = """
                {"steps":[{"op":"const","params":{"value":"hello"},"to":"greeting"}],"outputs":["greeting"]}
                """;

        // 建草稿
        mvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "tester")
                        .content(JSON.writeValueAsString(Map.of(
                                "code", code, "dslJson", dsl, "description", "API 测试"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        // 版本列表
        mvc.perform(get("/api/rules/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value(code));

        // 发布
        mvc.perform(post("/api/rules/" + code + "/versions/1/publish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // ACTIVE 查询
        mvc.perform(get("/api/rules/" + code + "/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        // 计算（无 docId）
        MvcResult compute = mvc.perform(post("/api/rules/" + code + "/compute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("input", Map.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andReturn();
        long compId = JSON.readTree(compute.getResponse().getContentAsString()).get("id").asLong();

        // 带 docId 计算：executeAndApply 落 extracted_field（source=RULE 标记）
        String docId = "doc-rule-src-" + System.nanoTime();
        mvc.perform(post("/api/rules/" + code + "/compute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("docId", docId, "input", Map.of()))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/documents/" + docId + "/lineage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[?(@.fieldKey=='greeting')].source").value("RULE"));

        // 计算详情
        mvc.perform(get("/api/rules/computations/" + compId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleCode").value(code));

        // 案件列表（应空）
        mvc.perform(get("/api/review-cases?status=OPEN"))
                .andExpect(status().isOk());

        // 归档
        mvc.perform(post("/api/rules/" + code + "/versions/1/archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
    }

    @Test
    void ruleNotFoundReturns404() throws Exception {
        mvc.perform(get("/api/rules/nonexistent-" + System.nanoTime()))
                .andExpect(status().isNotFound());
    }

    @Test
    void illegalPublishReturns409() throws Exception {
        String code = "conflict-" + System.nanoTime();
        String dsl = """
                {"steps":[{"op":"const","params":{"value":1},"to":"x"}],"outputs":["x"]}
                """;
        mvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "tester")
                        .content(JSON.writeValueAsString(Map.of("code", code, "dslJson", dsl))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/rules/" + code + "/versions/1/publish"))
                .andExpect(status().isOk());
        // 重复发布 → 409
        mvc.perform(post("/api/rules/" + code + "/versions/1/publish"))
                .andExpect(status().isConflict());
    }

    @Test
    void resolveCaseReturnsUpdatedStatus() throws Exception {
        String code = "case-api-" + System.nanoTime();
        String dsl = """
                {"steps":[{"op":"const","params":{"value":"v"},"to":"k"}],"outputs":["k"]}
                """;
        mvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "tester")
                        .content(JSON.writeValueAsString(Map.of("code", code, "dslJson", dsl))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/rules/" + code + "/versions/1/publish"))
                .andExpect(status().isOk());

        // 造一个案件（直接经 service 层创建，避免 LLM 依赖）
        // 这里用 materialDiff 触发自动建案
        String mdCode = "md-api-" + System.nanoTime();
        String mdDsl = """
                {"steps":[{"op":"materialDiff","params":{"left":"a","right":"b"},"to":"diff"}],"outputs":["diff"]}
                """;
        mvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "tester")
                        .content(JSON.writeValueAsString(Map.of("code", mdCode, "dslJson", mdDsl))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/rules/" + mdCode + "/versions/1/publish"))
                .andExpect(status().isOk());

        String docId = "doc-api-md-" + System.nanoTime();
        MvcResult comp = mvc.perform(post("/api/rules/" + mdCode + "/compute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "docId", docId,
                                "input", Map.of(
                                        "a", Map.of("amount", "100"),
                                        "b", Map.of("amount", "120"))))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode compNode = JSON.readTree(comp.getResponse().getContentAsString());
        assertThat(compNode.get("status").asText()).isEqualTo("SUCCESS");

        // 查案件列表
        MvcResult cases = mvc.perform(get("/api/review-cases?status=OPEN&docId=" + docId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode caseList = JSON.readTree(cases.getResponse().getContentAsString());
        assertThat(caseList.isArray()).isTrue();
        assertThat(caseList.size()).isGreaterThanOrEqualTo(1);
        long caseId = caseList.get(0).get("id").asLong();

        // 处置：APPROVE
        mvc.perform(post("/api/review-cases/" + caseId + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "approver")
                        .content(JSON.writeValueAsString(Map.of("action", "APPROVE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // 案件详情
        mvc.perform(get("/api/review-cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedBy").value("approver"));
    }
}
