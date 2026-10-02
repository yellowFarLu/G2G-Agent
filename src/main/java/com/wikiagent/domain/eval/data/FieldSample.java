package com.wikiagent.domain.eval.data;

import java.time.Instant;

/**
 * 字段抽取行投影（extracted_field → 评测）。
 *
 * @param docId    所属文档
 * @param fieldKey 字段键
 * @param valid    校验是否通过
 * @param at       行创建时间（窗口过滤依据）
 */
public record FieldSample(String docId, String fieldKey, boolean valid, Instant at) {
}
