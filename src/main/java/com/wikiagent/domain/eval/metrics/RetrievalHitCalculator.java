package com.wikiagent.domain.eval.metrics;

import com.wikiagent.domain.eval.EvalWindow;
import com.wikiagent.domain.eval.MetricValue;
import com.wikiagent.domain.eval.data.EvalDataSource;
import com.wikiagent.domain.eval.data.RetrievalSample;

/**
 * 2) retrievalHitRate 检索命中率。
 * <p>
 * 数据源：metric_event 中 eventType=RETRIEVED 的行，相关性由 kb_feedback
 * （USEFUL=相关 / USELESS=不相关）或 golden 对照给出。
 * 无反馈信号（relevant=null）的检索行不计入分母——不伪造相关性标签。
 */
public final class RetrievalHitCalculator implements MetricCalculator {

    public static final String KEY = "retrievalHitRate";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public MetricValue compute(EvalDataSource data, EvalWindow window) {
        long judged = 0;
        long hit = 0;
        for (RetrievalSample r : data.retrievals()) {
            if (!"RETRIEVED".equals(r.eventType()) || !window.contains(r.at())) {
                continue;
            }
            if (r.relevant() == null) {
                continue;
            }
            judged++;
            if (r.relevant()) {
                hit++;
            }
        }
        if (judged == 0) {
            return MetricValue.missing(KEY, "窗口内无带相关性标签（kb_feedback/golden）的 RETRIEVED 事件");
        }
        return MetricValue.of(KEY, (double) hit / judged);
    }
}
