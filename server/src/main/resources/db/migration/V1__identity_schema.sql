CREATE TABLE user_account (
    id UUID PRIMARY KEY,
    username VARCHAR(32) NOT NULL,
    username_normalized VARCHAR(32) NOT NULL UNIQUE,
    full_name VARCHAR(120) NOT NULL,
    email VARCHAR(254) NOT NULL,
    email_normalized VARCHAR(254) NOT NULL UNIQUE,
    address VARCHAR(255) NOT NULL,
    birth_date DATE NOT NULL,
    gender VARCHAR(16) NOT NULL CHECK (gender IN ('female', 'male', 'other', 'undisclosed')),
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'user' CHECK (role IN ('user', 'admin')),
    locked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_session (
    session_hash CHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);

CREATE INDEX user_session_user_id_idx ON user_session (user_id);
CREATE INDEX user_session_expires_at_idx ON user_session (expires_at);

CREATE TABLE password_reset_token (
    token_hash CHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX password_reset_token_user_id_idx ON password_reset_token (user_id);

CREATE TABLE login_history (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX login_history_user_time_idx ON login_history (user_id, occurred_at DESC);

CREATE TABLE admin_audit_event (
    id UUID PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES user_account (id),
    action VARCHAR(64) NOT NULL,
    target_id UUID NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::JSONB,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX admin_audit_event_time_idx ON admin_audit_event (occurred_at DESC);
