package com.wikiagent.application.task;

import com.wikiagent.domain.task.ErrorCode;
import com.wikiagent.domain.task.FatalTaskException;
import com.wikiagent.domain.task.RetryableTaskException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.regex.Pattern;

/**
 * 失败分类器（规格 3.3）：异常 → (error_code, 是否可重试)。
 * 判定顺序：任务异常自带码 → 超时类 → HTTP 状态位 → 文档损坏关键词 → 参数错误 → 兜底 INTERNAL。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.task.enabled", havingValue = "true")
public class ErrorClassifier {

    /** 分类结论：code 落 task_instance.error_code；retryable 决定 RETRY 还是 FAIL。 */
    public record Decision(ErrorCode code, boolean retryable) {
    }

    private static final Pattern P_5XX = Pattern.compile("\\b5\\d{2}\\b");
    private static final Pattern P_4XX = Pattern.compile("\\b4\\d{2}\\b");

    public Decision classify(Throwable t) {
        if (t instanceof FatalTaskException f) {
            return new Decision(f.getErrorCode(), false);
        }
        if (t instanceof RetryableTaskException r) {
            return new Decision(r.getErrorCode(), true);
        }
        if (t instanceof SocketTimeoutException || t instanceof ResourceAccessException) {
            return new Decision(ErrorCode.TIMEOUT, true);
        }
        String msg = t.getMessage() == null ? "" : t.getMessage();
        if (P_5XX.matcher(msg).find()) {
            return new Decision(ErrorCode.THIRD_PARTY_5XX, true);
        }
        if (P_4XX.matcher(msg).find()) {
            return new Decision(ErrorCode.THIRD_PARTY_4XX, false);
        }
        if (t instanceof IOException && containsAny(msg, "损坏", "加密", "不支持")) {
            return new Decision(ErrorCode.PARSE_FAILED, true);
        }
        if (t instanceof IllegalArgumentException) {
            return new Decision(ErrorCode.VALIDATION_FAILED, false);
        }
        return new Decision(ErrorCode.INTERNAL, true);
    }

    private static boolean containsAny(String msg, String... keywords) {
        for (String k : keywords) {
            if (msg.contains(k)) {
                return true;
            }
        }
        return false;
    }
}
