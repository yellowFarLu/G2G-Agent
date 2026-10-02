package com.wikiagent.application.extract;

import com.wikiagent.domain.extract.FieldDef;
import com.wikiagent.domain.extract.FieldValueType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 字段校验器（JSON Schema 子集）：required / type / regex / enum。
 * 每个字段返回错误信息列表（空列表 = 通过），供修复重试提示与 REVIEW 原因聚合。
 */
@Component
public class FieldValidator {

    public List<String> validate(FieldDef def, String rawValue) {
        List<String> errors = new ArrayList<>();
        boolean empty = rawValue == null || rawValue.isBlank();
        if (empty) {
            if (def.required()) {
                errors.add("字段 " + def.name() + " 为必填但缺失");
            }
            return errors; // 可选字段为空：其余校验不适用
        }
        String value = rawValue.strip();
        switch (def.type() == null ? FieldValueType.STRING : def.type()) {
            case NUMBER -> {
                try {
                    new BigDecimal(value);
                } catch (NumberFormatException e) {
                    errors.add("字段 " + def.name() + " 不是合法数字: " + value);
                }
            }
            case BOOLEAN -> {
                if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                    errors.add("字段 " + def.name() + " 不是合法布尔值: " + value);
                }
            }
            case DATE -> {
                try {
                    LocalDate.parse(value.length() >= 10 ? value.substring(0, 10) : value);
                } catch (Exception e) {
                    errors.add("字段 " + def.name() + " 不是合法日期(yyyy-MM-dd): " + value);
                }
            }
            case STRING -> {
                // 字符串类型无额外内置规则
            }
        }
        if (def.pattern() != null && !def.pattern().isBlank()) {
            try {
                if (!Pattern.matches(def.pattern(), value)) {
                    errors.add("字段 " + def.name() + " 不匹配正则 " + def.pattern() + ": " + value);
                }
            } catch (PatternSyntaxException e) {
                errors.add("字段 " + def.name() + " 正则定义非法: " + def.pattern());
            }
        }
        if (def.enumValues() != null && !def.enumValues().isEmpty()
                && def.enumValues().stream().noneMatch(v -> v.equalsIgnoreCase(value))) {
            errors.add("字段 " + def.name() + " 不在允许枚举 " + def.enumValues() + " 内: " + value);
        }
        return errors;
    }
}
