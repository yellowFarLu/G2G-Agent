package com.wikiagent.application.extract;

import com.wikiagent.config.ExtractProperties;
import com.wikiagent.domain.extract.ExtractedFieldValue;
import com.wikiagent.domain.extract.ExtractionReport;
import com.wikiagent.domain.parse.model.DocKind;
import com.wikiagent.domain.parse.model.ParsedDocument;
import com.wikiagent.domain.parse.model.ParsedPage;
import com.wikiagent.infrastructure.extract.LlmFieldExtractionClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B4 结构化提取编排（录制的 LLM 响应固件，无真实模型）：
 * 首轮合法直出 / 校验失败修复一次 / 低置信转 REVIEW / 必填缺失仍失败转 REVIEW。
 */
class FieldExtractionServiceTest {

    /** 录制式假客户端：按队列返回响应，并记录修复反馈。 */
    static class ScriptedClient extends LlmFieldExtractionClient {
        final List<String> answers;
        final List<String> feedbacks = new ArrayList<>();
        int calls;

        ScriptedClient(List<String> answers) {
            super(null); // 不触达真实 ChatModel
            this.answers = answers;
        }

        @Override
        public String call(String systemPrompt, String documentText, String repairFeedback) {
            feedbacks.add(repairFeedback);
            calls++;
            return answers.get(calls - 1);
        }
    }

    private static ExtractionSchemaRegistry registry;
    private static String fixture(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/fixtures/extract/" + name));
    }

    @BeforeAll
    static void initRegistry() {
        registry = new ExtractionSchemaRegistry();
        registry.load();
    }

    private ParsedDocument invoiceDoc() {
        return ParsedDocument.of(DocKind.PDF, "", List.of(
                ParsedPage.text(1, "发票号码：12345678 开票日期：2026年03月15日 销售方名称：北京云智科技有限公司"),
                ParsedPage.text(2, "价税合计（小写）￥1280.50")));
    }

    private FieldExtractionService service(ScriptedClient client) {
        return new FieldExtractionService(registry, new FieldValidator(), client, new ExtractProperties());
    }

    private ExtractedFieldValue field(ExtractionReport report, String key) {
        return report.fields().stream().filter(f -> f.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void validFirstRoundMapsFieldsWithEvidenceAndConfidence() throws Exception {
        ScriptedClient client = new ScriptedClient(List.of(fixture("invoice-ok.json")));
        ExtractionReport report = service(client).extract("doc-1", "invoice-demo", invoiceDoc());

        assertThat(client.calls).isEqualTo(1);
        assertThat(client.feedbacks.get(0)).isNull();
        assertThat(report.needsReview()).isFalse();
        assertThat(report.schemaVersion()).isEqualTo("1.0");
        assertThat(report.fields()).hasSize(5); // 可选 buyer 缺失不占位

        ExtractedFieldValue amount = field(report, "amount");
        assertThat(amount.value()).isEqualTo("1280.50");
        assertThat(amount.valid()).isTrue();
        assertThat(amount.confidence()).isEqualTo(0.91);
        assertThat(amount.evidence()).hasSize(1);
        assertThat(amount.evidence().get(0).pageNo()).isEqualTo(2);
        assertThat(amount.evidence().get(0).snippet()).contains("1280.50");
    }

    @Test
    void validationFailureTriggersOneRepairRoundWithErrors() throws Exception {
        ScriptedClient client = new ScriptedClient(List.of(
                fixture("invoice-bad.json"), fixture("invoice-ok.json")));
        ExtractionReport report = service(client).extract("doc-2", "invoice-demo", invoiceDoc());

        assertThat(client.calls).isEqualTo(2);
        assertThat(client.feedbacks.get(0)).isNull();
        String feedback = client.feedbacks.get(1);
        assertThat(feedback).contains("invoiceNo").contains("amount").contains("currency");

        assertThat(report.needsReview()).isFalse();
        assertThat(field(report, "invoiceNo").value()).isEqualTo("12345678");
        assertThat(field(report, "amount").valid()).isTrue();
        assertThat(field(report, "currency").valid()).isTrue();
    }

    @Test
    void lowConfidenceFieldTurnsReportIntoReview() throws Exception {
        ScriptedClient client = new ScriptedClient(List.of(fixture("invoice-low-confidence.json")));
        ExtractionReport report = service(client).extract("doc-3", "invoice-demo", invoiceDoc());

        assertThat(client.calls).isEqualTo(1); // 校验全过，仅低置信 → 不触发修复
        assertThat(report.needsReview()).isTrue();
        ExtractedFieldValue seller = field(report, "seller");
        assertThat(seller.valid()).isTrue(); // 校验通过
        assertThat(seller.confidence()).isEqualTo(0.42);
        assertThat(report.reviewReasons()).anyMatch(r -> r.contains("seller") && r.contains("0.75"));
    }

    @Test
    void missingRequiredAfterRepairStillFlagsReview() throws Exception {
        ScriptedClient client = new ScriptedClient(List.of(
                fixture("invoice-missing-seller.json"), fixture("invoice-missing-seller.json")));
        ExtractionReport report = service(client).extract("doc-4", "invoice-demo", invoiceDoc());

        assertThat(client.calls).isEqualTo(2);
        assertThat(report.needsReview()).isTrue();
        ExtractedFieldValue seller = field(report, "seller");
        assertThat(seller.valid()).isFalse();
        assertThat(seller.value()).isNull();
        assertThat(seller.errors()).anyMatch(e -> e.contains("必填"));
    }

    @Test
    void unknownSchemaRejected() {
        ScriptedClient client = new ScriptedClient(List.of("{}"));
        assertThatThrownBy(() -> service(client).extract("doc-5", "nope", invoiceDoc()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未注册的抽取 schema");
        assertThat(client.calls).isZero();
    }

    @Test
    void outOfRangeEvidencePageNoIsDroppedButSnippetKept() throws Exception {
        String json = """
                {"fields":[{"key":"invoiceNo","value":"12345678","confidence":0.9,
                "evidence":[{"pageNo":99,"snippet":"发票号码：12345678"}]}]}
                """;
        ScriptedClient client = new ScriptedClient(List.of(json, json));
        ExtractionReport report = service(client).extract("doc-6", "invoice-demo", invoiceDoc());
        ExtractedFieldValue invoiceNo = field(report, "invoiceNo");
        assertThat(invoiceNo.evidence()).hasSize(1);
        assertThat(invoiceNo.evidence().get(0).pageNo()).isNull();
        assertThat(invoiceNo.evidence().get(0).snippet()).contains("发票号码");
    }
}
