package com.wikiagent.application.task;

import com.wikiagent.domain.task.ErrorCode;
import com.wikiagent.domain.task.FatalTaskException;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 8 失败分类器测试：7 类 error_code 映射与重试判定（规格 3.3）。
 */
class ErrorClassifierTest {

    private final ErrorClassifier classifier = new ErrorClassifier();

    @Test
    void socketTimeoutIsRetryableTimeout() {
        ErrorClassifier.Decision d = classifier.classify(new SocketTimeoutException("read timed out"));
        assertThat(d.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(d.retryable()).isTrue();
    }

    @Test
    void messageWith503IsThirdParty5xx() {
        ErrorClassifier.Decision d = classifier.classify(new RuntimeException("upstream returned 503"));
        assertThat(d.code()).isEqualTo(ErrorCode.THIRD_PARTY_5XX);
        assertThat(d.retryable()).isTrue();
    }

    @Test
    void messageWith400IsThirdParty4xxNotRetryable() {
        ErrorClassifier.Decision d = classifier.classify(new RuntimeException("upstream returned 400"));
        assertThat(d.code()).isEqualTo(ErrorCode.THIRD_PARTY_4XX);
        assertThat(d.retryable()).isFalse();
    }

    @Test
    void fatalExceptionCarriesCodeAndNeverRetries() {
        ErrorClassifier.Decision d = classifier.classify(
                new FatalTaskException(ErrorCode.BUDGET_EXCEEDED, "预算耗尽"));
        assertThat(d.code()).isEqualTo(ErrorCode.BUDGET_EXCEEDED);
        assertThat(d.retryable()).isFalse();
    }

    @Test
    void corruptDocumentKeywordIsParseFailed() {
        ErrorClassifier.Decision d = classifier.classify(new java.io.IOException("文档已损坏，无法解析"));
        assertThat(d.code()).isEqualTo(ErrorCode.PARSE_FAILED);
        assertThat(d.retryable()).isTrue();
    }

    @Test
    void plainRuntimeExceptionIsInternalRetryable() {
        ErrorClassifier.Decision d = classifier.classify(new RuntimeException("boom"));
        assertThat(d.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(d.retryable()).isTrue();
    }

    @Test
    void illegalArgumentIsValidationFailedNotRetryable() {
        ErrorClassifier.Decision d = classifier.classify(new IllegalArgumentException("参数非法"));
        assertThat(d.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(d.retryable()).isFalse();
    }
}
