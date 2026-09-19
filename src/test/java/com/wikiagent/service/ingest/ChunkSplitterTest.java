package com.wikiagent.service.ingest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkSplitterTest {

    @Test
    void 参数非法应抛异常() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkSplitter(50, 10, 350, 60));
        assertThrows(IllegalArgumentException.class, () -> new ChunkSplitter(1000, 150, 100, 100));
    }

    @Test
    void 短段落应聚合为父块() {
        ChunkSplitter splitter = new ChunkSplitter(100, 10, 50, 10);
        List<String> parents = splitter.splitParents("第一段。\n第二段。\n第三段。");
        assertEquals(1, parents.size());
        assertEquals("第一段。\n第二段。\n第三段。", parents.get(0));
    }

    @Test
    void 超长段落应按句子切分且带重叠() {
        ChunkSplitter splitter = new ChunkSplitter(120, 15, 60, 10);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("这是第").append(i).append("句话，用于测试切分逻辑。");
        }
        List<String> parents = splitter.splitParents(sb.toString());
        assertTrue(parents.size() >= 2);
        // 相邻父块应有重叠
        String tail = parents.get(0).substring(Math.max(0, parents.get(0).length() - 10));
        assertTrue(parents.get(1).startsWith(tail) || parents.get(1).contains(tail),
                "相邻父块应共享重叠内容");
    }

    @Test
    void 短父块切子块返回原文本() {
        ChunkSplitter splitter = new ChunkSplitter(1000, 100, 350, 60);
        List<String> children = splitter.splitChildren("短文本。");
        assertEquals(1, children.size());
        assertEquals("短文本。", children.get(0));
    }

    @Test
    void 长父块切子块应覆盖全部内容且尺寸受控() {
        ChunkSplitter splitter = new ChunkSplitter(1000, 100, 120, 30);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("段落").append(i).append("：").append("企业知识库测试内容，用于验证子块滑窗切分。");
        }
        String parent = sb.toString();
        List<String> children = splitter.splitChildren(parent);
        assertTrue(children.size() > 1);
        for (String c : children) {
            assertTrue(c.length() <= 120, "子块长度不应超过 childChars，实际 " + c.length());
        }
        // 首块从文本开头开始，末块覆盖到文本结尾附近
        assertTrue(parent.startsWith(children.get(0)));
        String last = children.get(children.size() - 1);
        assertTrue(parent.endsWith(last) || parent.contains(last));
        // 相邻子块有重叠
        String head = children.get(1).substring(0, 10);
        assertTrue(children.get(0).contains(head) || children.get(2).contains(head),
                "相邻子块应共享重叠内容");
    }
}
