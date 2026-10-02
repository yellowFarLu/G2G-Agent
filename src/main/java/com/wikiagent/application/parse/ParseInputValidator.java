package com.wikiagent.application.parse;

import com.wikiagent.config.ParseProperties;
import com.wikiagent.domain.parse.model.DocKind;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * 入库文件入口校验（B §2.3）：扩展名白名单、空文件、超大文件（wikiagent.parse.max-file-mb）。
 * 校验失败抛 IllegalArgumentException（handler 归类 VALIDATION_FAILED，不重试）。
 */
@Component
public class ParseInputValidator {

    private static final Set<String> TEXT_EXT = Set.of("txt", "md", "markdown");
    private static final Set<String> OOXML_EXT = Set.of("docx", "xlsx");
    private static final Set<String> LEGACY_EXT = Set.of("doc", "xls");
    private static final Set<String> IMAGE_EXT = Set.of("png", "jpg", "jpeg", "tif", "tiff", "bmp");
    private static final Set<String> AUDIO_EXT = Set.of("mp3", "wav", "m4a", "aac", "flac", "ogg");

    private final ParseProperties props;

    public ParseInputValidator(ParseProperties props) {
        this.props = props;
    }

    public void validate(String filename, byte[] bytes) {
        String ext = extOf(filename);
        if (supportedExts().stream().noneMatch(e -> e.equals(ext))) {
            throw new IllegalArgumentException("不支持的文档类型: ." + ext);
        }
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("上传文件为空: " + filename);
        }
        long maxBytes = (long) Math.max(1, props.getMaxFileMb()) * 1024L * 1024L;
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException(
                    "文件超过大小上限 " + props.getMaxFileMb() + "MB（实际 "
                            + (bytes.length / 1024 / 1024) + "MB）: " + filename);
        }
    }

    /** 页文本字符数低于该值视为扫描页（转 OCR）。 */
    public int scannedThreshold() {
        return props.getScannedPageCharThreshold();
    }

    public DocKind kindOf(String filename) {
        String ext = extOf(filename);
        if (TEXT_EXT.contains(ext)) {
            return DocKind.TEXT;
        }
        if ("pdf".equals(ext)) {
            return DocKind.PDF;
        }
        if (OOXML_EXT.contains(ext)) {
            return DocKind.OFFICE_OOXML;
        }
        if (LEGACY_EXT.contains(ext)) {
            return DocKind.OFFICE_LEGACY;
        }
        if (IMAGE_EXT.contains(ext)) {
            return DocKind.IMAGE;
        }
        return DocKind.AUDIO;
    }

    /** 图片扩展名 → MIME（OCR 请求用）。 */
    public static String imageMime(String ext) {
        return switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "tif", "tiff" -> "image/tiff";
            case "bmp" -> "image/bmp";
            default -> "image/png";
        };
    }

    public static String extOf(String filename) {
        if (filename == null) {
            throw new IllegalArgumentException("文件名为空");
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            throw new IllegalArgumentException("文件缺少扩展名: " + filename);
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private Set<String> supportedExts() {
        return Set.of("txt", "md", "markdown", "pdf", "docx", "xlsx", "doc", "xls",
                "png", "jpg", "jpeg", "tif", "tiff", "bmp",
                "mp3", "wav", "m4a", "aac", "flac", "ogg");
    }
}
