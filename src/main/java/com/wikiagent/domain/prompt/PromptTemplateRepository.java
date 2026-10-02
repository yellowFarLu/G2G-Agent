package com.wikiagent.domain.prompt;

import java.util.Optional;

/**
 * 提示词模板仓储端口（domain 层接口，由 infrastructure 实现）。
 */
public interface PromptTemplateRepository {

    /** 按 code 查当前 ACTIVE 版本模板；不存在返回 empty。 */
    Optional<PromptTemplate> findActiveByCode(String code);

    /** 保存模板（新增版本或状态流转）。 */
    PromptTemplate save(PromptTemplate template);
}
