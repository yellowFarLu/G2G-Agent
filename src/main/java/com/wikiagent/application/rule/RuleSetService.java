package com.wikiagent.application.rule;

import com.wikiagent.domain.rule.RuleSet;
import com.wikiagent.domain.rule.RuleStatus;
import com.wikiagent.dto.ConflictException;
import com.wikiagent.dto.NotFoundException;
import com.wikiagent.entity.rule.RuleSetEntity;
import com.wikiagent.repo.rule.RuleSetRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;

/**
 * 规则集生命周期服务：草稿 → 发布 → 归档（版本化 + checksum 防篡改）。
 */
@Service
public class RuleSetService {

    private final RuleSetRepo repo;

    public RuleSetService(RuleSetRepo repo) {
        this.repo = repo;
    }

    @Transactional
    public RuleSet createDraft(String code, String dslJson, String description, String createdBy) {
        int nextVersion = repo.findByCodeOrderByVersionDesc(code).stream()
                .findFirst().map(RuleSetEntity::getVersion).orElse(0) + 1;
        Instant now = Instant.now();
        RuleSetEntity e = new RuleSetEntity();
        e.setCode(code);
        e.setVersion(nextVersion);
        e.setStatus(RuleStatus.DRAFT.name());
        e.setDslJson(dslJson);
        e.setChecksum(sha256(dslJson));
        e.setDescription(description);
        e.setCreatedBy(createdBy);
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return toDomain(repo.save(e));
    }

    @Transactional
    public RuleSet publish(String code, int version) {
        // #6：先 FOR UPDATE 锁定同 code 全部行再做状态迁移。
        // 两个并发发布在同一行集合上排队，后持有者读到的已是最新提交状态，
        // 从根上消除 check-then-save 竞态（修复前可出现同 code 两条 ACTIVE）。
        List<RuleSetEntity> locked = repo.findLockByCode(code);
        RuleSetEntity target = locked.stream()
                .filter(x -> x.getVersion() == version)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("规则不存在: " + code + " v" + version));
        if (target.getStatus().equals(RuleStatus.ACTIVE.name())) {
            throw new ConflictException("规则已是 ACTIVE 状态");
        }
        Instant now = Instant.now();
        // 同 code 其他 ACTIVE 转 ARCHIVED
        for (RuleSetEntity e : locked) {
            if (e.getStatus().equals(RuleStatus.ACTIVE.name())) {
                e.setStatus(RuleStatus.ARCHIVED.name());
                e.setUpdatedAt(now);
                repo.save(e);
            }
        }
        target.setStatus(RuleStatus.ACTIVE.name());
        target.setPublishedAt(now);
        target.setUpdatedAt(now);
        return toDomain(repo.save(target));
    }

    @Transactional
    public RuleSet archive(String code, int version) {
        RuleSetEntity target = repo.findByCodeAndVersion(code, version)
                .orElseThrow(() -> new NotFoundException("规则不存在: " + code + " v" + version));
        if (!target.getStatus().equals(RuleStatus.ACTIVE.name())) {
            throw new ConflictException("仅 ACTIVE 规则可归档");
        }
        target.setStatus(RuleStatus.ARCHIVED.name());
        target.setUpdatedAt(Instant.now());
        return toDomain(repo.save(target));
    }

    public RuleSet get(String code, int version) {
        return repo.findByCodeAndVersion(code, version)
                .map(this::toDomain)
                .orElseThrow(() -> new NotFoundException("规则不存在: " + code + " v" + version));
    }

    public List<RuleSet> listByCode(String code) {
        return repo.findByCodeOrderByVersionDesc(code).stream().map(this::toDomain).toList();
    }

    private RuleSet toDomain(RuleSetEntity e) {
        return new RuleSet(e.getId(), e.getCode(), e.getVersion(),
                RuleStatus.valueOf(e.getStatus()), e.getDslJson(), e.getChecksum(),
                e.getDescription(), e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt(),
                e.getPublishedAt());
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
