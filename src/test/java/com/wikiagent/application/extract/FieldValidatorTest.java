package com.wikiagent.application.extract;

import com.wikiagent.domain.extract.FieldDef;
import com.wikiagent.domain.extract.FieldValueType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B4 字段校验器：required/type/regex/enum 四类规则。
 */
class FieldValidatorTest {

    private final FieldValidator validator = new FieldValidator();

    private FieldDef field(String name, FieldValueType type, boolean required,
                           String pattern, List<String> enums) {
        return new FieldDef(name, name, type, required, pattern, enums, null);
    }

    @Test
    void requiredMissingRejectsEmptyAndNull() {
        FieldDef f = field("a", FieldValueType.STRING, true, null, List.of());
        assertThat(validator.validate(f, null)).hasSize(1);
        assertThat(validator.validate(f, "   ")).hasSize(1);
    }

    @Test
    void optionalEmptyPasses() {
        FieldDef f = field("a", FieldValueType.NUMBER, false, null, List.of());
        assertThat(validator.validate(f, null)).isEmpty();
    }

    @Test
    void typeChecksNumberDateBoolean() {
        assertThat(validator.validate(field("n", FieldValueType.NUMBER, true, null, List.of()), "12.5")).isEmpty();
        assertThat(validator.validate(field("n", FieldValueType.NUMBER, true, null, List.of()), "abc")).hasSize(1);
        assertThat(validator.validate(field("d", FieldValueType.DATE, true, null, List.of()), "2026-03-15")).isEmpty();
        assertThat(validator.validate(field("d", FieldValueType.DATE, true, null, List.of()), "15/03/2026")).hasSize(1);
        assertThat(validator.validate(field("b", FieldValueType.BOOLEAN, true, null, List.of()), "true")).isEmpty();
        assertThat(validator.validate(field("b", FieldValueType.BOOLEAN, true, null, List.of()), "yes")).hasSize(1);
    }

    @Test
    void regexAndEnumChecked() {
        FieldDef code = field("code", FieldValueType.STRING, true, "^[0-9]{6}$", List.of());
        assertThat(validator.validate(code, "123456")).isEmpty();
        assertThat(validator.validate(code, "abc")).hasSize(1);

        FieldDef currency = field("currency", FieldValueType.STRING, true, null, List.of("CNY", "USD"));
        assertThat(validator.validate(currency, "cny")).isEmpty(); // 枚举大小写不敏感
        assertThat(validator.validate(currency, "RMB")).hasSize(1);
    }

    @Test
    void illegalRegexReportedAsDefinitionError() {
        FieldDef bad = field("x", FieldValueType.STRING, true, "([0-9", List.of());
        assertThat(validator.validate(bad, "123")).hasSize(1)
                .allMatch(e -> e.contains("正则定义非法"));
    }
}
