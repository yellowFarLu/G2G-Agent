package com.wikiagent.domain.extract;

/**
 * 字段值证据片段：来源页 + 原文片段；{@code artifactId} 在 C 阶段产物落库后回填。
 */
public record FieldEvidence(Integer pageNo, String snippet, String artifactId, String bbox) {

    public FieldEvidence(Integer pageNo, String snippet) {
        this(pageNo, snippet, null, null);
    }
}
