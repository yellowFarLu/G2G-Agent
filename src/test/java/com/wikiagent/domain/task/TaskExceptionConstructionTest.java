package com.wikiagent.domain.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 3 领域异常构造契约测试：异常携带的错误码/人工接管字段/控制信号继承关系必须稳定，
 * 后续 worker（Task 8）与 API（Task 10）按此消费。
 */
class TaskExceptionConstructionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fatalTaskExceptionCarriesErrorCode() {
        FatalTaskException ex = new FatalTaskException(ErrorCode.VALIDATION_FAILED, "x");
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(ex.getMessage()).isEqualTo("x");
    }

    @Test
    void retryableTaskExceptionCarriesErrorCode() {
        RetryableTaskException ex = new RetryableTaskException(ErrorCode.TIMEOUT, "timeout happened");
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(ex.getMessage()).isEqualTo("timeout happened");
    }

    @Test
    void humanRequiredExceptionCarriesTakeoverFields() {
        JsonNode schema = mapper.createObjectNode().put("field", "order_id");
        HumanRequiredException ex = new HumanRequiredException(
                HumanTaskKind.INPUT, "补全订单号", "请人工核对订单号后提交", schema);
        assertThat(ex.getKind()).isEqualTo(HumanTaskKind.INPUT);
        assertThat(ex.getTitle()).isEqualTo("补全订单号");
        assertThat(ex.getInstruction()).isEqualTo("请人工核对订单号后提交");
        assertThat(ex.getFormSchema()).isEqualTo(schema);
    }

    @Test
    void pauseSignalIsControlSignalAndRuntime() {
        PauseSignalException ex = new PauseSignalException();
        assertThat(ex).isInstanceOf(ControlSignalException.class);
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void cancelSignalIsControlSignalAndRuntime() {
        CancelSignalException ex = new CancelSignalException();
        assertThat(ex).isInstanceOf(ControlSignalException.class);
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void controlSignalExceptionIsRuntime() {
        ControlSignalException ex = new ControlSignalException("control");
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}
