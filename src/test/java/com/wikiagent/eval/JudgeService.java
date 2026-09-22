package com.wikiagent.eval;

import java.util.Arrays;
import java.util.List;

/**
 * v1-v2 §9 Agent 评测 - 评分服务（测试工具类，非 Spring Bean）。
 * <p>
 * 提供基于关键词匹配的简单评分逻辑：
 * <ul>
 *   <li>{@link #score(String, String)} - 计算 answer 中包含的 groundTruth 关键词比例（0.0-1.0）</li>
 *   <li>{@link #isIntentCorrect(String, String)} - 判断实际意图是否与期望意图一致</li>
 * </ul>
 * groundTruth 为逗号分隔的关键词列表，例如 "差旅,报销,标准"。
 */
public class JudgeService {

    /**
     * 计算 answer 中包含的 groundTruth 关键词比例。
     *
     * @param answer     待评分的答案文本
     * @param groundTruth 逗号分隔的期望关键词列表（如 "差旅,报销,标准"）
     * @return 0.0-1.0 的得分（包含关键词数 / 总关键词数）
     */
    public double score(String answer, String groundTruth) {
        if (answer == null || answer.isBlank() || groundTruth == null || groundTruth.isBlank()) {
            return 0.0;
        }

        List<String> keywords = Arrays.stream(groundTruth.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();

        if (keywords.isEmpty()) {
            return 0.0;
        }

        String lowerAnswer = answer.toLowerCase();
        long matched = keywords.stream()
                .filter(kw -> lowerAnswer.contains(kw.toLowerCase()))
                .count();

        return (double) matched / keywords.size();
    }

    /**
     * 判断实际意图是否与期望意图一致（大小写不敏感）。
     *
     * @param actualIntent   实际路由的意图 code
     * @param expectedIntent 期望的意图 code
     * @return true 表示一致
     */
    public boolean isIntentCorrect(String actualIntent, String expectedIntent) {
        if (actualIntent == null || expectedIntent == null) {
            return false;
        }
        return actualIntent.equalsIgnoreCase(expectedIntent);
    }
}
