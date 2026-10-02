package com.wikiagent.infrastructure.lineage;

import com.wikiagent.domain.lineage.ArtifactType;
import com.wikiagent.domain.lineage.DocArtifact;
import com.wikiagent.entity.lineage.DocArtifactEntity;
import com.wikiagent.repo.lineage.DocArtifactRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 文档产物存储（C1.2）：大文本/JSON 落盘 {@code data/uploads/{docId}/artifacts/}，
 * DB 仅存 contentRef + sha256 + size。同一 (docId, versionNo, type, pageNo) 幂等覆盖。
 */
@Component
public class ArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(ArtifactStore.class);

    private final DocArtifactRepo repo;

    public ArtifactStore(DocArtifactRepo repo) {
        this.repo = repo;
    }

    /** 保存字符串内容产物。 */
    public DocArtifact saveText(String docId, int versionNo, ArtifactType type,
                                Integer pageNo, String content) {
        byte[] bytes = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
        return saveBytes(docId, versionNo, type, pageNo, bytes, type.name().toLowerCase() + ".txt");
    }

    /** 保存二进制/JSON 产物。 */
    public DocArtifact saveBytes(String docId, int versionNo, ArtifactType type,
                                 Integer pageNo, byte[] bytes, String fileName) {
        String sha256 = sha256Hex(bytes);
        Path dir = Path.of("data", "uploads", docId, "artifacts");
        try {
            Files.createDirectories(dir);
            String pageSuffix = pageNo == null ? "" : ("_p" + pageNo);
            Path target = dir.resolve("v" + versionNo + "_" + type.name().toLowerCase()
                    + pageSuffix + "_" + fileName);
            Files.write(target, bytes);
            DocArtifactEntity existing = repo.findByDocIdAndVersionNoAndArtifactTypeAndPageNo(
                    docId, versionNo, type.name(), pageNo).orElse(null);
            DocArtifactEntity entity = existing == null ? new DocArtifactEntity() : existing;
            entity.setDocId(docId);
            entity.setVersionNo(versionNo);
            entity.setArtifactType(type.name());
            entity.setContentRef(target.toString());
            entity.setSha256(sha256);
            entity.setSizeBytes(bytes.length);
            entity.setPageNo(pageNo);
            entity.setCreatedAt(Instant.now());
            DocArtifactEntity saved = repo.save(entity);
            return toDomain(saved);
        } catch (Exception e) {
            throw new IllegalStateException("保存产物失败 docId=" + docId + " type=" + type
                    + ": " + e.getMessage(), e);
        }
    }

    public String readText(DocArtifact artifact) {
        try {
            return Files.readString(Path.of(artifact.contentRef()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取产物失败 ref=" + artifact.contentRef() + ": " + e.getMessage(), e);
        }
    }

    public byte[] readBytes(DocArtifact artifact) {
        try {
            return Files.readAllBytes(Path.of(artifact.contentRef()));
        } catch (Exception e) {
            throw new IllegalStateException("读取产物失败 ref=" + artifact.contentRef() + ": " + e.getMessage(), e);
        }
    }

    public DocArtifact find(String docId, int versionNo, ArtifactType type, Integer pageNo) {
        return repo.findByDocIdAndVersionNoAndArtifactTypeAndPageNo(
                docId, versionNo, type.name(), pageNo).map(ArtifactStore::toDomain).orElse(null);
    }

    public static DocArtifact toDomain(DocArtifactEntity e) {
        return new DocArtifact(e.getId(), e.getDocId(), e.getVersionNo(),
                ArtifactType.valueOf(e.getArtifactType()), e.getContentRef(),
                e.getSha256(), e.getSizeBytes(), e.getPageNo(), e.getCreatedAt());
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
