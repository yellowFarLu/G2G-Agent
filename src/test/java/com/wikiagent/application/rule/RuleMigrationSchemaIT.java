package com.wikiagent.application.rule;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #5 Flyway 迁移方言回归：真实 Flyway（H2 MODE=MySQL）跑完全量迁移后，
 * 断言 V13/V14/V15 的表/列/索引就位（CREATE INDEX / ADD COLUMN 去掉 IF NOT EXISTS
 * 后在 H2 仍可执行；MySQL 8.0 语法兼容性由迁移脚本审查保证——这两种写法 MySQL 8.0 原生支持）。
 * 不新建重型基建：复用既有 @SpringBootTest + Flyway 体系，仅做 INFORMATION_SCHEMA 轻量断言。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class RuleMigrationSchemaIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void v13V14V15TablesExist() {
        assertThat(tableExists("rule_set")).isTrue();
        assertThat(tableExists("rule_computation")).isTrue();
        assertThat(tableExists("review_case")).isTrue();
        assertThat(tableExists("prompt_template")).isTrue();
        assertThat(tableExists("model_call_log")).isTrue();
        assertThat(tableExists("tool_permission")).isTrue();
    }

    @Test
    void v13ReviewCaseColumnsExist() {
        assertThat(columnExists("review_case", "case_type")).isTrue();
        assertThat(columnExists("review_case", "doc_id")).isTrue();
        assertThat(columnExists("review_case", "version_no")).isTrue();
        assertThat(columnExists("review_case", "diff_json")).isTrue();
        assertThat(columnExists("review_case", "human_task_id")).isTrue();
        assertThat(columnExists("review_case", "task_id")).isTrue();
        assertThat(columnExists("review_case", "rule_code")).isTrue();
        assertThat(columnExists("review_case", "computation_id")).isTrue();
        assertThat(columnExists("review_case", "resolution_json")).isTrue();
    }

    @Test
    void v11V12AddedColumnsExist() {
        // V11：kb_child_chunk.page_no；knowledge_metadata.page_no/snippet/artifact_id
        assertThat(columnExists("kb_child_chunk", "page_no")).isTrue();
        assertThat(columnExists("knowledge_metadata", "page_no")).isTrue();
        assertThat(columnExists("knowledge_metadata", "snippet")).isTrue();
        assertThat(columnExists("knowledge_metadata", "artifact_id")).isTrue();
        // V12：extracted_field.page_no/snippet；kb_child_chunk.version_no/is_active
        assertThat(columnExists("extracted_field", "page_no")).isTrue();
        assertThat(columnExists("extracted_field", "snippet")).isTrue();
        assertThat(columnExists("kb_child_chunk", "version_no")).isTrue();
        assertThat(columnExists("kb_child_chunk", "is_active")).isTrue();
    }

    @Test
    void v13V14IndexesCreated() {
        assertThat(indexExists("idx_review_case_task")).isTrue();
        assertThat(indexExists("idx_rule_set_code")).isTrue();
        assertThat(indexExists("idx_model_call_purpose")).isTrue();
        assertThat(indexExists("idx_child_doc_active")).isTrue();
    }

    @Test
    void flywayMarkedV13ToV15Success() {
        Integer success = jdbc.queryForObject("""
                select count(*) from flyway_schema_history
                where success = true and version in ('13', '14', '15')
                """, Integer.class);
        assertThat(success).isEqualTo(3);
    }

    private boolean tableExists(String table) {
        List<String> names = jdbc.queryForList("""
                select table_name from information_schema.tables
                where table_schema = 'PUBLIC' and table_name = ?
                """, String.class, table.toUpperCase());
        return !names.isEmpty();
    }

    private boolean columnExists(String table, String column) {
        List<String> names = jdbc.queryForList("""
                select column_name from information_schema.columns
                where table_schema = 'PUBLIC' and table_name = ? and column_name = ?
                """, String.class, table.toUpperCase(), column.toUpperCase());
        return !names.isEmpty();
    }

    private boolean indexExists(String index) {
        List<String> names = jdbc.queryForList("""
                select index_name from information_schema.indexes
                where table_schema = 'PUBLIC' and index_name = ?
                """, String.class, index.toUpperCase());
        return !names.isEmpty();
    }
}
