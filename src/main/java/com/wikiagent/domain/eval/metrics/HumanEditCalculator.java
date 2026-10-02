package com.wikiagent.domain.eval.metrics;

import com.wikiagent.domain.eval.EvalWindow;
import com.wikiagent.domain.eval.MetricValue;
import com.wikiagent.domain.eval.data.EvalDataSource;
import com.wikiagent.domain.eval.data.ReviewDisposition;

/**
 * 5) humanEditRate 人工修改率。
 * <p>
 * 数据源：review_case（V13）。EDTED / (APPROVED + REJECTED + EDITED)；
 * OPEN 未处置案件不计入分母。无法识别的状态串保守忽略（不计入、不编造）。
 * 无已处置案件 → missing。
 */
public final class HumanEditCalculator implements MetricCalculator {

    public static final String KEY = "humanEditRate";

    private static final String APPROVED = "APPROVED";
    private static final String REJECTED = "REJECTED";
    private static final String EDITED = "EDITED";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public MetricValue compute(EvalDataSource data, EvalWindow window) {
        long approved = 0;
        long rejected = 0;
        long edited = 0;
        for (ReviewDisposition r : data.reviewDispositions()) {
            if (!window.contains(r.at())) {
                continue;
            }
            String status = r.status();
            if (APPROVED.equalsIgnoreCase(status)) {
                approved++;
            } else if (REJECTED.equalsIgnoreCase(status)) {
                rejected++;
            } else if (EDITED.equalsIgnoreCase(status)) {
                edited++;
            }
        }
        long decided = approved + rejected + edited;
        if (decided == 0) {
            return MetricValue.missing(KEY, "窗口内无已处置 review_case（APPROVED/REJECTED/EDITED）");
        }
        return MetricValue.of(KEY, (double) edited / decided);
    }
}
