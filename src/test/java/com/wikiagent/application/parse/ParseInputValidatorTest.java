package com.wikiagent.application.parse;

import com.wikiagent.config.ParseProperties;
import com.wikiagent.domain.parse.model.DocKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * B-2 入口校验：白名单/空文件/超大（VALIDATION，不重试）与介质分流。
 */
class ParseInputValidatorTest {

    private ParseInputValidator validator(int maxMb) {
        ParseProperties p = new ParseProperties();
        p.setMaxFileMb(maxMb);
        return new ParseInputValidator(p);
    }

    @Test
    void rejectsUnsupportedExtensionEmptyAndOversize() {
        ParseInputValidator v = validator(1);

        assertThat(catchThrowable(() -> v.validate("a.xyz", new byte[]{1})))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持");
        assertThat(catchThrowable(() -> v.validate("a.pdf", new byte[0])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("为空");
        assertThat(catchThrowable(() -> v.validate("big.pdf", new byte[2 * 1024 * 1024])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("大小上限");
        assertThat(catchThrowable(() -> v.validate("noext", new byte[]{1})))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsSupportedTypesUpToLimit() {
        ParseInputValidator v = validator(1);
        v.validate("a.PDF", new byte[10]);
        v.validate("b.doc", new byte[10]);
        v.validate("c.tiff", new byte[10]);
        v.validate("d.m4a", new byte[10]);
    }

    @Test
    void routesKindsAndMimes() {
        ParseInputValidator v = validator(10);
        assertThat(v.kindOf("a.txt")).isEqualTo(DocKind.TEXT);
        assertThat(v.kindOf("a.pdf")).isEqualTo(DocKind.PDF);
        assertThat(v.kindOf("a.docx")).isEqualTo(DocKind.OFFICE_OOXML);
        assertThat(v.kindOf("a.xls")).isEqualTo(DocKind.OFFICE_LEGACY);
        assertThat(v.kindOf("a.jpg")).isEqualTo(DocKind.IMAGE);
        assertThat(v.kindOf("a.mp3")).isEqualTo(DocKind.AUDIO);
        assertThat(ParseInputValidator.imageMime("jpg")).isEqualTo("image/jpeg");
        assertThat(ParseInputValidator.imageMime("tiff")).isEqualTo("image/tiff");
        assertThat(ParseInputValidator.imageMime("x.png")).isEqualTo("image/png");
    }
}
