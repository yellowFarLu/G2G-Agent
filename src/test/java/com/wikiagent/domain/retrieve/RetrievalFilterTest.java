package com.wikiagent.domain.retrieve;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E5 检索权限过滤表达式单测：解析 / matches / toMilvusExpr / and 合并。
 */
class RetrievalFilterTest {

    @Test
    void 空表达式不过滤() {
        assertTrue(RetrievalFilter.parse(null).isEmpty());
        assertTrue(RetrievalFilter.parse("").isEmpty());
        assertTrue(RetrievalFilter.parse("   ").isEmpty());
        assertNull(RetrievalFilter.parse(null).toMilvusExpr());
    }

    @Test
    void 等值条件解析与匹配() {
        RetrievalFilter f = RetrievalFilter.parse("domain='industry'");
        assertFalse(f.isEmpty());
        assertEquals("domain_tag == \"industry\"", f.toMilvusExpr());
        assertTrue(f.matches("industry", null, null));
        assertFalse(f.matches("pms", null, null));
    }

    @Test
    void IN条件解析与匹配() {
        RetrievalFilter f = RetrievalFilter.parse("identity IN ('admin','product')");
        assertEquals("required_identity in [\"admin\",\"product\"]", f.toMilvusExpr());
        assertTrue(f.matches(null, null, "admin"));
        assertTrue(f.matches(null, null, "product"));
        assertFalse(f.matches(null, null, "test"));
    }

    @Test
    void AND合取全部满足才放行() {
        RetrievalFilter f = RetrievalFilter.parse("domain='industry' AND identity IN ('admin')");
        assertEquals("domain_tag == \"industry\" and required_identity in [\"admin\"]", f.toMilvusExpr());
        assertTrue(f.matches("industry", "faq", "admin"));
        assertFalse(f.matches("industry", "faq", "product"));
        assertFalse(f.matches("pms", "faq", "admin"));
    }

    @Test
    void 未知字段与解析失败片段被丢弃() {
        assertTrue(RetrievalFilter.parse("foo='bar'").isEmpty());
        assertTrue(RetrievalFilter.parse("not a condition").isEmpty());
        // 有效片段保留、无效片段丢弃
        RetrievalFilter f = RetrievalFilter.parse("foo='bar' AND domain='industry'");
        assertEquals("domain_tag == \"industry\"", f.toMilvusExpr());
    }

    @Test
    void 双引号与双等号兼容() {
        RetrievalFilter f = RetrievalFilter.parse("domain==\"industry\"");
        assertEquals("domain_tag == \"industry\"", f.toMilvusExpr());
        assertTrue(f.matches("industry", null, null));
    }

    @Test
    void 字段别名归一化() {
        assertEquals("domain_tag == \"industry\"",
                RetrievalFilter.parse("domain_tag='industry'").toMilvusExpr());
        assertEquals("sub_domain_tag == \"faq\"",
                RetrievalFilter.parse("subDomain='faq'").toMilvusExpr());
        assertEquals("required_identity in [\"admin\"]",
                RetrievalFilter.parse("required_identity IN ('admin')").toMilvusExpr());
    }

    @Test
    void 未打标元数据默认放行() {
        RetrievalFilter f = RetrievalFilter.parse("domain='industry'");
        assertTrue(f.matches(null, null, null)); // 三值全 null = 未打标公共知识
        assertFalse(f.matches(null, "faq", null)); // 部分打标：缺失维度不满足等值条件
    }

    @Test
    void and合并过滤器() {
        RetrievalFilter a = RetrievalFilter.parse("domain='industry'");
        RetrievalFilter b = RetrievalFilter.parse("identity IN ('admin')");
        assertEquals("domain_tag == \"industry\" and required_identity in [\"admin\"]",
                a.and(b).toMilvusExpr());
        assertSame(a, a.and(RetrievalFilter.none()));
        assertSame(b, RetrievalFilter.none().and(b));
        assertSame(a, a.and(null));
    }

    @Test
    void 身份上下文构造过滤器() {
        try {
            RetrievalSecurityContext.clear();
            assertTrue(RetrievalSecurityContext.identityFilter().isEmpty());
            RetrievalSecurityContext.setIdentity("admin");
            RetrievalFilter f = RetrievalSecurityContext.identityFilter();
            assertEquals("required_identity in [\"admin\"]", f.toMilvusExpr());
            RetrievalSecurityContext.setIdentity("   ");
            assertTrue(RetrievalSecurityContext.identityFilter().isEmpty());
        } finally {
            RetrievalSecurityContext.clear();
        }
    }
}
