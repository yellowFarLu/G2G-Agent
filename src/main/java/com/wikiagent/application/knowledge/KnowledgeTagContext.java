package com.wikiagent.application.knowledge;

/**
 * v4 §6.6.1 知识入库打标上下文。
 * <p>
 * 文档入库时携带的 9×6 垂直隔离标签 + 创建者元数据，
 * 写入 {@code knowledge_metadata}（每条子 chunk 一行），供：
 * <ul>
 *   <li>检索期身份权限过滤（v3 §6.5）</li>
 *   <li>知识看板元数据展示（创建时间/创建者/创建身份，v4 §6.6.2）</li>
 *   <li>冲突扫描按领域分组（v4 §6.6.3）</li>
 * </ul>
 *
 * @param domainTag        9 个垂直领域 code（{@link com.wikiagent.domain.identity.DomainTag}）
 * @param subDomainTag     6 个知识类型 code（{@link com.wikiagent.domain.identity.SubDomainTag}）
 * @param requiredIdentity 访问该知识所需最低身份（admin/business/product/tech/testing）
 * @param createdBy        创建者 userId
 * @param createdIdentity  创建者身份（与 requiredIdentity 区分：创建者当时身份快照）
 * @param sourceFilename   源文件名
 */
public record KnowledgeTagContext(String domainTag,
                                  String subDomainTag,
                                  String requiredIdentity,
                                  String createdBy,
                                  String createdIdentity,
                                  String sourceFilename) {

    /** 未显式打标时的保守默认值：最严身份要求，避免未分类知识被低权限用户检索到。 */
    public static KnowledgeTagContext defaultFor(String sourceFilename) {
        return new KnowledgeTagContext(
                "industry_solution",
                "business",
                "admin",
                "anonymous",
                "admin",
                sourceFilename);
    }
}
