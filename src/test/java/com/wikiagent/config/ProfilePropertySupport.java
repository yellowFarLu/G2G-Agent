package com.wikiagent.config;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

/**
 * J1 profile 加载测试辅助：从 Environment 中按优先级读取 property 的<b>原始字面值</b>，
 * 不触发占位符解析（这样才能断言 ${MYSQL_PASSWORD} 这类无默认值占位，
 * 而无需在测试中真正提供环境变量）。
 */
final class ProfilePropertySupport {

    private ProfilePropertySupport() {
    }

    /** 返回最高优先级 PropertySource 中的原始值；不存在返回 null。 */
    static String raw(ConfigurableEnvironment env, String key) {
        for (PropertySource<?> ps : env.getPropertySources()) {
            if (ps instanceof EnumerablePropertySource<?> eps) {
                String[] names = eps.getPropertyNames();
                for (String name : names) {
                    if (name.equals(key)) {
                        Object v = eps.getProperty(name);
                        return v == null ? null : String.valueOf(v);
                    }
                }
            }
        }
        return null;
    }

    /** 必须是"无默认值"的纯环境变量占位（如 ${MYSQL_PASSWORD}），不允许 ${VAR:root} 之类。 */
    static void assertRequiredPlaceholder(String raw, String expectedVariable) {
        if (raw == null || !raw.matches("^\\$\\{[A-Z0-9_]+\\}$")) {
            throw new AssertionError("期望无默认值占位 ${...}，实际=" + raw);
        }
        if (!raw.equals("${" + expectedVariable + "}")) {
            throw new AssertionError("期望占位 ${" + expectedVariable + "}，实际=" + raw);
        }
    }
}
