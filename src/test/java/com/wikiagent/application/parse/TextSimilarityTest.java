package com.wikiagent.application.parse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B3 归一化文本相似度：大小写/标点/空白折叠与 Levenshtein 比例。
 */
class TextSimilarityTest {

    @Test
    void identicalAndEmptyCases() {
        assertThat(TextSimilarity.similarity("abc", "abc")).isEqualTo(1.0);
        assertThat(TextSimilarity.similarity("", "")).isEqualTo(1.0);
        assertThat(TextSimilarity.similarity(null, null)).isEqualTo(1.0);
        assertThat(TextSimilarity.diffRate("abc", "abc")).isZero();
    }

    @Test
    void normalizesCasePunctuationAndWhitespace() {
        assertThat(TextSimilarity.similarity("Hello, World!", "hello world")).isEqualTo(1.0);
        assertThat(TextSimilarity.similarity("甲，乙；丙", "甲乙丙")).isEqualTo(1.0);
        assertThat(TextSimilarity.similarity("a  b\nc", "a b c")).isEqualTo(1.0);
    }

    @Test
    void measuresPartialDifference() {
        double sim = TextSimilarity.similarity("发票号码1234", "发票号码5678");
        assertThat(sim).isGreaterThan(0.0).isLessThan(1.0);
        assertThat(TextSimilarity.similarity("发票号码1234", "完全不同的文字XYZ")).isLessThan(0.5);
    }
}
