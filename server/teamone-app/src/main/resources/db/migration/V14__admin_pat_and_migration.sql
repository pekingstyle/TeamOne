-- ============================================================================
-- V14: P0 企业治理底座与数据迁移（PAT 访问令牌与审计增强）
-- ============================================================================

-- ① 个人访问令牌表（Personal Access Token，用于 Git CLI / OpenAPI 长期安全凭据）
CREATE TABLE IF NOT EXISTS platform.personal_access_token (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES platform.app_user(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    token_prefix VARCHAR(16) NOT NULL,
    scopes VARCHAR(255) NOT NULL DEFAULT 'all',
    expires_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_pat_user ON platform.personal_access_token(user_id);
CREATE INDEX IF NOT EXISTS idx_pat_hash ON platform.personal_access_token(token_hash);

COMMENT ON TABLE platform.personal_access_token IS '个人访问令牌（PAT）：用于终端 Git 客户端与外部 API 鉴权，数据库仅存储 SHA-256 哈希';
COMMENT ON COLUMN platform.personal_access_token.token_hash IS '令牌 SHA-256 单向哈希密文（不可逆，防泄露）';
COMMENT ON COLUMN platform.personal_access_token.token_prefix IS '令牌前缀展示（如 t1_pat_a1b2c3，脱敏呈现）';
COMMENT ON COLUMN platform.personal_access_token.scopes IS '权限作用域（如 repo:read,repo:write,api 等逗号分隔）';

-- ② 审计日志高效检索索引强化
CREATE INDEX IF NOT EXISTS idx_audit_log_actor ON audit.audit_log(actor_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_action ON audit.audit_log(action);
CREATE INDEX IF NOT EXISTS idx_audit_log_created_at ON audit.audit_log(created_at DESC);
