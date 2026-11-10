CREATE TABLE mls_key_package (
    user_id UUID PRIMARY KEY REFERENCES user_account (id) ON DELETE CASCADE,
    key_package BYTEA NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
