package com.wikiagent.application.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.config.ExtractProperties;
import com.wikiagent.domain.extract.ExtractedFieldValue;
import com.wikiagent.domain.extract.ExtractionReport;
import com.wikiagent.domain.extract.ExtractionSchema;
import com.wikiagent.domain.extract.FieldDef;
import com.wikiagent.domain.extract.FieldEvidence;
import com.wikiagent.domain.extract.FieldSource;
import com.wikiagent.domain.extract.FieldValueType;
import com.wikiagent.domain.parse.model.ParsedDocument;
import com.wikiagent.domain.parse.model.ParsedPage;
import com.wikiagent.infrastructure.extract.LlmFieldExtractionClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构化字段提取编排：schema → 提示词 → LLM JSON 输出 → 字段校验；
 * 首轮存在解析失败/校验失败时带错误反馈修复重试（默认 1 次）；
 * 仍失败或置信度低于阈值 → {@link ExtractionReport#needsReview()}，由流水线转人工 REVIEW。
 */
@Service
public class FieldExtractionService {

    private static final Logger log = LoggerFactory.getLogger(FieldExtractionService.class);
    private static final double DEFAULT_CONFIDENCE = 1.0;

    private final ExtractionSchemaRegistry registry;
    private final FieldValidator validator;
    private final LlmFieldExtractionClient llmClient;
    private final ExtractProperties props;
    private final ObjectMapper mapper;

    public FieldExtractionService(ExtractionSchemaRegistry registry, FieldValidator validator,
                                  LlmFieldExtractionClient llmClient, ExtractProperties props) {
        this.registry = registry;
        this.validator = validator;
        this.llmClient = llmClient;
        this.props = props;
        // 模型可能返回含裸换行等 JSON 文本，与视觉解析同样容错
        this.mapper = new ObjectMapper()
                .enable(com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature());
    }

    public ExtractionReport extract(String docId, String schemaKey, ParsedDocument doc) {
        ExtractionSchema schema = registry.get(schemaKey)
                .orElseThrow(() -> new IllegalArgumentException("未注册的抽取 schema: " + schemaKey));
        String systemPrompt = buildSystemPrompt(schema);
        String documentText = buildDocumentText(doc);

        Map<String, RawField> accepted = new LinkedHashMap<>();
        List<String> lastErrors = List.of("首轮尚未调用");
        String feedback = null;
        int attempts = props.getRepairAttempts() + 1;
        for (int round = 1; round <= attempts; round++) {
            String raw = llmClient.call(systemPrompt, documentText, feedback);
            List<String> parseErrors = new ArrayList<>();
            List<RawField> drafts = parse(raw, parseErrors);
            Map<String, List<String>> validateErrors = new LinkedHashMap<>();
            for (RawField draft : drafts) {
                FieldDef def = schema.field(draft.key()).orElse(null);
                if (def == null) {
                    validateErrors.put(draft.key(), List.of("schema 中不存在该字段: " + draft.key()));
                    continue;
                }
                List<String> errs = validator.validate(def, draft.value());
                if (!errs.isEmpty()) {
                    validateErrors.put(draft.key(), errs);
                }
            }
            for (FieldDef def : schema.fields()) {
                if (def.required() && drafts.stream().noneMatch(d -> d.key().equals(def.name())
                        && d.value() != null && !d.value().isBlank())) {
                    validateErrors.computeIfAbsent(def.name(),
                            k -> new ArrayList<>()).add("必填字段缺失: " + def.name());
                }
            }
            if (parseErrors.isEmpty() && validateErrors.isEmpty()) {
                for (RawField d : drafts) {
                    accepted.put(d.key(), d);
                }
                lastErrors = List.of();
                break;
            }
            lastErrors = new ArrayList<>(parseErrors);
            validateErrors.values().forEach(lastErrors::addAll);
            log.info("文档 {} 字段抽取第 {} 轮未通过校验（{} 项问题），准备修复",
                    docId, round, lastErrors.size());
            feedback = String.join("\n", lastErrors);
            // 最后一轮仍失败：保留这轮解析出的字段（带错误标记）进报告
            if (round == attempts) {
                for (RawField d : drafts) {
                    accepted.put(d.key(), d);
                }
            }
        }
        return assemble(schema, accepted, lastErrors, doc);
    }

    private ExtractionReport assemble(ExtractionSchema schema, Map<String, RawField> accepted,
                                      List<String> unresolvedErrors, ParsedDocument doc) {
        List<ExtractedFieldValue> fields = new ArrayList<>();
        List<String> reviewReasons = new ArrayList<>(unresolvedErrors);
        double threshold = props.getLowConfidenceThreshold();
        for (FieldDef def : schema.fields()) {
            RawField raw = accepted.get(def.name());
            if (raw == null) {
                if (def.required()) {
                    fields.add(new ExtractedFieldValue(def.name(), null, def.type(),
                            0, FieldSource.MODEL, false,
                            List.of("必填字段缺失: " + def.name()), List.of()));
                }
                continue;
            }
            List<String> errs = validator.validate(def, raw.value());
            List<FieldEvidence> evidence = sanitizeEvidence(raw.evidence(), doc);
            double confidence = clamp(raw.confidence() == null ? DEFAULT_CONFIDENCE : raw.confidence());
            boolean low = raw.value() != null && !raw.value().isBlank() && confidence < threshold;
            if (low) {
                reviewReasons.add("字段 " + def.name() + " 置信度 " + confidence
                        + " 低于阈值 " + threshold);
            }
            if (!errs.isEmpty()) {
                reviewReasons.addAll(errs);
            }
            fields.add(new ExtractedFieldValue(def.name(), raw.value(),
                    def.type() == null ? FieldValueType.STRING : def.type(), confidence,
                    FieldSource.MODEL, errs.isEmpty(), errs, evidence));
        }
        boolean needsReview = !reviewReasons.isEmpty();
        return new ExtractionReport(schema.key(), schema.version(), fields,
                needsReview, reviewReasons);
    }

    private List<FieldEvidence> sanitizeEvidence(List<RawEvidence> raw, ParsedDocument doc) {
        if (raw == null) {
            return List.of();
        }
        int maxPage = doc.pages().stream().mapToInt(ParsedPage::pageNo).max().orElse(0);
        List<FieldEvidence> out = new ArrayList<>();
        for (RawEvidence e : raw) {
            if (e == null || e.snippet() == null || e.snippet().isBlank()) {
                continue;
            }
            Integer pageNo = e.pageNo();
            if (pageNo != null && maxPage > 0 && (pageNo < 1 || pageNo > maxPage)) {
                pageNo = null; // 模型乱报页码则丢弃页码但保留片段
            }
            out.add(new FieldEvidence(pageNo, e.snippet()));
        }
        return out;
    }

    private List<RawField> parse(String raw, List<String> errors) {
        if (raw == null || raw.isBlank()) {
            errors.add("模型返回为空");
            return List.of();
        }
        String json = stripCodeFence(raw);
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode array = root.path("fields");
            if (!array.isArray()) {
                errors.add("模型返回 JSON 缺少 fields 数组");
                return List.of();
            }
            List<RawField> fields = new ArrayList<>();
            for (JsonNode node : array) {
                String key = node.path("key").asText(null);
                if (key == null || key.isBlank()) {
                    continue;
                }
                String value = node.hasNonNull("value") ? node.get("value").asText() : null;
                Double confidence = node.hasNonNull("confidence")
                        ? node.get("confidence").asDouble() : null;
                List<RawEvidence> evidence = new ArrayList<>();
                JsonNode ev = node.path("evidence");
                if (ev.isArray()) {
                    for (JsonNode e : ev) {
                        Integer pageNo = e.hasNonNull("pageNo") ? e.get("pageNo").asInt() : null;
                        String snippet = e.path("snippet").asText(null);
                        if (snippet != null) {
                            evidence.add(new RawEvidence(pageNo, snippet));
                        }
                    }
                }
                fields.add(new RawField(key, value, confidence, evidence));
            }
            return fields;
        } catch (Exception e) {
            errors.add("模型返回不是合法 JSON: " + e.getMessage());
            return List.of();
        }
    }

    private static String stripCodeFence(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) {
                s = s.substring(firstNewline + 1);
            }
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3);
            }
        }
        return s.trim();
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private String buildDocumentText(ParsedDocument doc) {
        if (!doc.pages().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (ParsedPage page : doc.pages()) {
                sb.append("[第").append(page.pageNo()).append("页]\n")
                        .append(page.text() == null ? "" : page.text()).append("\n\n");
            }
            return sb.toString();
        }
        return doc.fullText();
    }

    private String buildSystemPrompt(ExtractionSchema schema) {
        StringBuilder sb = new StringBuilder()
                .append("你是严格的文档字段抽取器。按下列字段定义从文档抽取结构化数据。\n")
                .append("只输出 JSON（不要 Markdown、解释文字），格式：\n")
                .append("{\"fields\":[{\"key\":\"字段名\",\"value\":\"字符串值\",")
                .append("\"confidence\":0.0到1.0,")
                .append("\"evidence\":[{\"pageNo\":1,\"snippet\":\"原文片段\"}]}]}\n")
                .append("证据必须来自原文（页码对应 [第N页] 标记，片段为原文摘录）。\n")
                .append("Schema: ").append(schema.key()).append(" v").append(schema.version()).append('\n');
        for (FieldDef f : schema.fields()) {
            sb.append("- ").append(f.name())
                    .append("（类型 ").append(f.type() == null ? FieldValueType.STRING : f.type())
                    .append(f.required() ? "，必填" : "，可选");
            if (f.pattern() != null) {
                sb.append("，正则 ").append(f.pattern());
            }
            if (!f.enumValues().isEmpty()) {
                sb.append("，枚举 ").append(f.enumValues());
            }
            if (f.description() != null) {
                sb.append("：").append(f.description());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private record RawField(String key, String value, Double confidence, List<RawEvidence> evidence) {
    }

    private record RawEvidence(Integer pageNo, String snippet) {
    }
}
