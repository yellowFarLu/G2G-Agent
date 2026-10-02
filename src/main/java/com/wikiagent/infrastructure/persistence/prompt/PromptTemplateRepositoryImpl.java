package com.wikiagent.infrastructure.persistence.prompt;

import com.wikiagent.domain.prompt.PromptTemplate;
import com.wikiagent.domain.prompt.PromptTemplateRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * 提示词模板仓储 JPA 实现。
 */
@Repository
public class PromptTemplateRepositoryImpl implements PromptTemplateRepository {

    private final PromptTemplateJpaDao dao;

    public PromptTemplateRepositoryImpl(PromptTemplateJpaDao dao) {
        this.dao = dao;
    }

    @Override
    public Optional<PromptTemplate> findActiveByCode(String code) {
        return dao.findByCodeAndStatus(code, "ACTIVE").map(this::toDomain);
    }

    @Override
    public PromptTemplate save(PromptTemplate template) {
        PromptTemplateEntity e = toEntity(template);
        e.setUpdatedAt(Instant.now());
        return toDomain(dao.save(e));
    }

    private PromptTemplate toDomain(PromptTemplateEntity e) {
        return new PromptTemplate(e.getId(), e.getCode(), e.getVersion() == null ? 1 : e.getVersion(),
                e.getContent(), PromptTemplate.Status.from(e.getStatus()), e.getCreatedAt(), e.getUpdatedAt());
    }

    private PromptTemplateEntity toEntity(PromptTemplate t) {
        PromptTemplateEntity e = new PromptTemplateEntity();
        e.setId(t.id());
        e.setCode(t.code());
        e.setVersion(t.version());
        e.setContent(t.content());
        e.setStatus(t.status() == null ? "DRAFT" : t.status().name());
        e.setCreatedAt(t.createdAt() == null ? Instant.now() : t.createdAt());
        e.setUpdatedAt(t.updatedAt() == null ? Instant.now() : t.updatedAt());
        return e;
    }
}
