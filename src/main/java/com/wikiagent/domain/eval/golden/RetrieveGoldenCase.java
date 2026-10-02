package com.wikiagent.domain.eval.golden;

import java.util.List;

/**
 * 检索黄金样本（retrieve-golden.json 数组元素）。
 * <p>
 * expectedChunkIds 必须与 resources/eval/seed/retrieve-seed.json 造数的 kb_child_chunk id
 * 一一对应；评测运行器先播种再评测。检索结果在 docId 粒度断言（现网 Source 仅暴露 docId，
 * chunk→doc 映射由种子库解析；引用页码/snippet 一致性在引用正确率中逐源判定）。
 *
 * @param id                 样本 id
 * @param query              自然语言查询
 * @param domain             九大业务域标签（DomainTag code）
 * @param expectedChunkIds   期望命中的 chunk id（其所属 doc 必须出现在结果中）
 * @param forbiddenChunkIds  禁止命中的 chunk id（如已软删旧版本 chunk），可空
 */
public record RetrieveGoldenCase(String id,
                                 String query,
                                 String domain,
                                 List<String> expectedChunkIds,
                                 List<String> forbiddenChunkIds) {
}
