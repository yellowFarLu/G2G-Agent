package com.wikiagent.domain.eval.data;

import java.time.Instant;

/**
 * 单条引用判定结果（retrieve 类样本的每个 Source 判定一次）。
 *
 * @param docId      引用指向的文档
 * @param versionNo  引用标注的版本号
 * @param pageNo     引用标注的页码（可空）
 * @param resolvable docId+versionNo 是否可解析到 active 子块
 * @param consistent pageNo/snippet 是否与来源子块一致
 * @param at         判定时间（窗口过滤依据）
 */
public record CitationSample(String docId, int versionNo, Integer pageNo,
                             boolean resolvable, boolean consistent, Instant at) {
}
