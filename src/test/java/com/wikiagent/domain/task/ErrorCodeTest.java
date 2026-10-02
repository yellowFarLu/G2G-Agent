package com.wikiagent.domain.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 子项目 A Task 2：错误码可重试分类测试，逐码对应设计规格 3.3。
 * 可重试 = TIMEOUT / THIRD_PARTY_5XX / PARSE_FAILED / INTERNAL；
 * 不重试 = THIRD_PARTY_4XX / VALIDATION_FAILED / BUDGET_EXCEEDED。
 */
class ErrorCodeTest {

    static Stream<Arguments> spec33ErrorCodes() {
        return Stream.of(
                Arguments.arguments(ErrorCode.TIMEOUT, true),
                Arguments.arguments(ErrorCode.THIRD_PARTY_5XX, true),
                Arguments.arguments(ErrorCode.PARSE_FAILED, true),
                Arguments.arguments(ErrorCode.INTERNAL, true),
                Arguments.arguments(ErrorCode.THIRD_PARTY_4XX, false),
                Arguments.arguments(ErrorCode.VALIDATION_FAILED, false),
                Arguments.arguments(ErrorCode.BUDGET_EXCEEDED, false));
    }

    @ParameterizedTest(name = "retryable({0}) = {1}")
    @MethodSource("spec33ErrorCodes")
    void retryableMatchesSpec(ErrorCode code, boolean expectedRetryable) {
        assertThat(code.retryable()).isEqualTo(expectedRetryable);
    }

    @Test
    void errorCodeSetIsExactlySeven() {
        assertThat(ErrorCode.values()).hasSize(7);
    }
}
