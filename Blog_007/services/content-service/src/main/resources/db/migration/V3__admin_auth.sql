-- =====================================================================
-- V3 (Phase 4): the admin user and refresh tokens.
-- =====================================================================
CREATE TABLE admin_users (
    id             uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    username       varchar(50)  NOT NULL UNIQUE,
    display_name   varchar(100) NOT NULL,
    password_hash  varchar(200) NOT NULL,          -- BCrypt hash, NEVER the password
    role           varchar(20)  NOT NULL DEFAULT 'ADMIN',
    created_at     timestamptz  NOT NULL DEFAULT now()
);

-- Refresh tokens are long-lived (7 days), so we can revoke them. We store only a SHA-256 HASH of
-- each token: a database leak doesn't give an attacker usable tokens.
-- "family": all tokens created by rotating from one login. If an already-used token is presented
-- again (stolen!), the whole family is revoked ("reuse detection").
CREATE TABLE refresh_tokens (
    id           uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id    uuid         NOT NULL,
    user_id      uuid         NOT NULL REFERENCES admin_users (id) ON DELETE CASCADE,
    token_hash   char(64)     NOT NULL UNIQUE,      -- hex SHA-256
    expires_at   timestamptz  NOT NULL,
    used_at      timestamptz,                       -- set when rotated
    revoked_at   timestamptz,                       -- set on logout / reuse detection
    created_at   timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id);
