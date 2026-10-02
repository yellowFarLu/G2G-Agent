package com.wikiagent.infrastructure.parse;

import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * Tika 纯 Java 文本抽取（B §2.3）：旧版 .doc/.xls 等格式兜底，不依赖任何外部进程。
 * 抽取失败（损坏/加密）抛 IllegalStateException，由上层归类 PARSE_FAILED。
 */
@Component
public class TikaTextExtractor {

    public String extract(byte[] bytes, String filename) {
        Tika tika = new Tika();
        tika.setMaxStringLength(-1);
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            // 按文件内容自动探测 MIME（OLE2 容器可可靠识别 .doc/.xls）
            String text = tika.parseToString(in, new Metadata());
            return text == null ? "" : text.strip();
        } catch (Exception e) {
            throw new IllegalStateException("Tika 文本抽取失败(" + filename + "): " + e.getMessage(), e);
        }
    }
}
