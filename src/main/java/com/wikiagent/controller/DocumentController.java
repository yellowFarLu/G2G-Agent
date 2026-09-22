package com.wikiagent.controller;

import com.wikiagent.application.knowledge.KnowledgeTagContext;
import com.wikiagent.domain.identity.BusinessIdentity;
import com.wikiagent.domain.identity.DomainTag;
import com.wikiagent.domain.identity.SubDomainTag;
import com.wikiagent.dto.DocumentView;
import com.wikiagent.dto.NotFoundException;
import com.wikiagent.entity.KbDocument;
import com.wikiagent.repo.KbDocumentRepo;
import com.wikiagent.service.ingest.IngestionService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** 文档管理：上传（触发异步入库）、列表、详情、删除。 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final KbDocumentRepo docRepo;
    private final IngestionService ingestionService;

    public DocumentController(KbDocumentRepo docRepo, IngestionService ingestionService) {
        this.docRepo = docRepo;
        this.ingestionService = ingestionService;
    }

    @PostMapping
    public DocumentView upload(@RequestParam("file") MultipartFile file,
                               @RequestParam(required = false) String domain,
                               @RequestParam(required = false) String subDomain,
                               @RequestHeader(value = "X-User-Id", required = false) String userId,
                               @RequestHeader(value = "X-Business-Identity", required = false) String identity)
            throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的文件");
        }
        String filename = sanitizeFilename(file.getOriginalFilename());
        String ext = extensionOf(filename);
        if (ext.isBlank()) {
            throw new IllegalArgumentException("无法识别文件扩展名，支持 txt/md/pdf/docx/xlsx");
        }

        // v4 §6.6.1 打标参数校验：显式传入时必须是合法枚举 code
        if (domain != null && DomainTag.fromCode(domain) == null) {
            throw new IllegalArgumentException("非法领域标签 domain=" + domain);
        }
        if (subDomain != null && SubDomainTag.fromCode(subDomain) == null) {
            throw new IllegalArgumentException("非法知识类型标签 subDomain=" + subDomain);
        }
        if (identity != null && !isValidIdentity(identity)) {
            throw new IllegalArgumentException("非法业务身份 identity=" + identity);
        }
        KnowledgeTagContext tagContext = (domain != null && subDomain != null)
                ? new KnowledgeTagContext(domain, subDomain,
                    identity == null ? "business" : identity,
                    userId == null ? "anonymous" : userId,
                    identity == null ? "business" : identity,
                    filename)
                : KnowledgeTagContext.defaultFor(filename);

        String docId = UUID.randomUUID().toString();
        byte[] bytes = file.getBytes();
        Path dir = Path.of("data", "uploads", docId);
        Files.createDirectories(dir);
        Files.write(dir.resolve(filename), bytes);

        KbDocument doc = new KbDocument();
        doc.setId(docId);
        doc.setFilename(filename);
        doc.setDocType(ext);
        doc.setSizeBytes(bytes.length);
        doc.setStatus(KbDocument.PARSING);
        docRepo.save(doc);

        ingestionService.ingest(docId, filename, bytes, tagContext);
        return DocumentView.from(doc);
    }

    private static boolean isValidIdentity(String code) {
        for (BusinessIdentity i : BusinessIdentity.values()) {
            if (i.code().equalsIgnoreCase(code)) {
                return true;
            }
        }
        return false;
    }

    @GetMapping
    public List<DocumentView> list() {
        return docRepo.findAllByOrderByCreatedAtDesc().stream().map(DocumentView::from).toList();
    }

    @GetMapping("/{id}")
    public DocumentView detail(@PathVariable String id) {
        KbDocument doc = docRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("文档不存在: " + id));
        return DocumentView.from(doc);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        if (!docRepo.existsById(id)) {
            throw new NotFoundException("文档不存在: " + id);
        }
        ingestionService.delete(id);
    }

    private static String sanitizeFilename(String original) {
        if (original == null || original.isBlank()) {
            throw new IllegalArgumentException("文件名为空");
        }
        // 去掉可能的路径成分，避免目录穿越
        String name = Path.of(original.replace("\\", "/")).getFileName().toString().strip();
        if (name.isEmpty() || name.startsWith(".")) {
            throw new IllegalArgumentException("非法文件名: " + original);
        }
        return name;
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase();
    }
}
