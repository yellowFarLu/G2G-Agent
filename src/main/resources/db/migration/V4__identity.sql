-- V4__identity.sql: v3 §6.5 知识库领域垂直隔离与身份权限
-- 用户身份 → 可访问的 (domain, subDomain) 映射

CREATE TABLE IF NOT EXISTS user_identity (
    id                      BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id                 VARCHAR(64)  NOT NULL,
    business_identity       VARCHAR(32)  NOT NULL,   -- admin / business / product / technology / test
    allowed_domains         TEXT,                    -- JSON: ["industry_solutions", "merchant_center", ...]
    allowed_sub_domains     TEXT,                    -- JSON: {"industry_solutions": ["general", "faq"], ...}
    assigned_by             VARCHAR(64),             -- 管理员 user_id
    created_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_identity UNIQUE (user_id)
);

CREATE INDEX idx_identity_user ON user_identity (user_id);
CREATE INDEX idx_identity_role ON user_identity (business_identity);

-- 默认身份权限模板（管理员可 CRUD）
CREATE TABLE IF NOT EXISTS identity_permission_template (
    id                      BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    business_identity       VARCHAR(32)  NOT NULL,   -- admin / business / product / technology / test
    domain_tag              VARCHAR(64)  NOT NULL,   -- industry_solutions / merchant_center / ...
    sub_domain_tag          VARCHAR(64)  NOT NULL,   -- general / faq / ...
    can_read                BOOLEAN      NOT NULL DEFAULT FALSE,
    can_write               BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_identity_perm UNIQUE (business_identity, domain_tag, sub_domain_tag)
);
