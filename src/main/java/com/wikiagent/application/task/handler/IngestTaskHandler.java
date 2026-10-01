package com.wikiagent.application.task.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wikiagent.application.knowledge.KnowledgeTagContext;
import com.wikiagent.domain.task.ErrorCode;
import com.wikiagent.domain.task.FatalTaskException;
import com.wikiagent.domain.task.HumanRequiredException;
import com.wikiagent.domain.task.RetryableTaskException;
import com.wikiagent.domain.task.StepDef;
import com.wikiagent.domain.task.StepResult;
import com.wikiagent.domain.task.TaskExecutionContext;
import com.wikiagent.domain.task.TaskHandler;
import com.wikiagent.service.ingest.IngestionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 文档入库任务处理器（taskType=INGEST）：六步流水线
 * DOWNLOAD → PARSE → CLEAN → SPLIT → EMBED_AND_PERSIST → INDEX_VERIFY。
 * 文件字节不入库不进 MQ：DOWNLOAD 从 data/uploads/{docId}/{filename} 读取；
 * 步骤间大文本经派生文件（_parsed.txt/_cleaned.txt）传递，checkpoint 仅存校验摘要。
 * PARSE 异常分类：不支持/.doc → Fatal(VALIDATION_FAILED)；解析库失败（损坏/加密）→ Fatal(PARSE_FAILED)。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.task.enabled", havingValue = "true")
public class IngestTaskHandler implements TaskHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final IngestionService ingestion;

    public IngestTaskHandler(IngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @Override
    public String taskType() {
        return "INGEST";
    }

    @Override
    public List<StepDef> planSteps(JsonNode payloadArgs) {
        return List.of(
                StepDef.of(1, "DOWNLOAD", "读取上传文件"),
                StepDef.of(2, "PARSE", "解析文档"),
                StepDef.of(3, "CLEAN", "清洗文本"),
                StepDef.of(4, "SPLIT", "父子切分"),
                StepDef.of(5, "EMBED_AND_PERSIST", "向量化与索引"),
                StepDef.of(6, "INDEX_VERIFY", "索引校验"));
    }

    @Override
    public StepResult executeStep(TaskExecutionContext ctx)
            throws RetryableTaskException, FatalTaskException, HumanRequiredException {
        JsonNode args = ctx.args();
        String docId = args.path("docId").asText();
        String filename = args.path("filename").asText();
        return switch (ctx.currentStepNo()) {
            case 1 -> download(docId, filename);
            case 2 -> parse(docId, filename);
            case 3 -> clean(docId);
            case 4 -> split(ctx.args(), docId, filename);
            case 5 -> embed(docId);
            case 6 -> verify(docId);
            default -> throw new FatalTaskException(ErrorCode.INTERNAL,
                    "未知入库步骤: " + ctx.currentStepNo());
        };
    }

    private StepResult download(String docId, String filename) {
        Path file = uploadPath(docId, filename);
        if (!Files.exists(file)) {
            throw new FatalTaskException(ErrorCode.VALIDATION_FAILED, "上传文件不存在: " + file);
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) {
                throw new FatalTaskException(ErrorCode.VALIDATION_FAILED, "上传文件为空: " + filename);
            }
            ObjectNode cp = MAPPER.createObjectNode()
                    .put("sha256", sha256Hex(bytes))
                    .put("sizeBytes", bytes.length);
            return new StepResult(false, cp.toString(), 10, null);
        } catch (FatalTaskException e) {
            throw e;
        } catch (Exception e) {
            throw new FatalTaskException(ErrorCode.INTERNAL, "读取上传文件失败: " + e.getMessage(), e);
        }
    }

    private StepResult parse(String docId, String filename) {
        try {
            byte[] bytes = Files.readAllBytes(uploadPath(docId, filename));
            String raw = ingestion.parseStep(docId, filename, bytes);
            Files.writeString(parsedPath(docId), raw, StandardCharsets.UTF_8);
            return StepResult.done(25);
        } catch (IllegalArgumentException e) {
            // .doc/.xls 不支持、未知扩展名 → 校验类致命失败（不重试）
            throw new FatalTaskException(ErrorCode.VALIDATION_FAILED, e.getMessage(), e);
        } catch (IllegalStateException e) {
            // PDFBox/POI 解析失败（损坏/加密）→ 解析类致命失败（不重试）
            throw new FatalTaskException(ErrorCode.PARSE_FAILED, e.getMessage(), e);
        } catch (Exception e) {
            throw new FatalTaskException(ErrorCode.INTERNAL, "解析步骤 IO 失败: " + e.getMessage(), e);
        }
    }

    private StepResult clean(String docId) {
        try {
            String raw = Files.readString(parsedPath(docId), StandardCharsets.UTF_8);
            String cleaned = ingestion.cleanStep(docId, raw);
            Files.writeString(cleanedPath(docId), cleaned, StandardCharsets.UTF_8);
            return StepResult.done(40);
        } catch (Exception e) {
            throw new FatalTaskException(ErrorCode.INTERNAL, "清洗步骤失败: " + e.getMessage(), e);
        }
    }

    private StepResult split(JsonNode args, String docId, String filename) {
        try {
            String cleaned = Files.readString(cleanedPath(docId), StandardCharsets.UTF_8);
            IngestionService.IngestOutcome outcome =
                    ingestion.splitStep(docId, cleaned, tagContextOf(args, filename));
            ObjectNode cp = MAPPER.createObjectNode()
                    .put("parentCount", outcome.parentCount())
                    .put("childCount", outcome.childCount());
            return new StepResult(false, cp.toString(), 55, null);
        } catch (FatalTaskException e) {
            throw e;
        } catch (Exception e) {
            throw new FatalTaskException(ErrorCode.INTERNAL, "切分步骤失败: " + e.getMessage(), e);
        }
    }

    /** Milvus/向量化瞬时异常不上抛致命——交由 worker 按 INTERNAL 重试退避。 */
    private StepResult embed(String docId) {
        boolean persisted = ingestion.embedAndPersistStep(docId);
        return persisted ? StepResult.done(85) : StepResult.skipped();
    }

    private StepResult verify(String docId) {
        ingestion.finalizeStep(docId);
        return StepResult.done(100, "kb-doc:" + docId);
    }

    private KnowledgeTagContext tagContextOf(JsonNode args, String filename) {
        String domain = args.path("domain").asText(null);
        String subDomain = args.path("subDomain").asText(null);
        if (domain == null || domain.isBlank() || subDomain == null || subDomain.isBlank()) {
            return KnowledgeTagContext.defaultFor(filename);
        }
        String identity = args.path("identity").asText("business");
        String userId = args.path("userId").asText("anonymous");
        return new KnowledgeTagContext(domain, subDomain, identity, userId, identity, filename);
    }

    private static Path uploadPath(String docId, String filename) {
        return Path.of("data", "uploads", docId, filename);
    }

    private static Path parsedPath(String docId) {
        return Path.of("data", "uploads", docId, "_parsed.txt");
    }

    private static Path cleanedPath(String docId) {
        return Path.of("data", "uploads", docId, "_cleaned.txt");
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(bytes)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
