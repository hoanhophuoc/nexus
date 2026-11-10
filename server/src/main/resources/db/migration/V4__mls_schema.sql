ALTER TABLE conversation ADD COLUMN current_epoch BIGINT NOT NULL DEFAULT 0;
ALTER TABLE conversation_member ADD COLUMN joined_epoch BIGINT NOT NULL DEFAULT 0;

CREATE TABLE mls_commit (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversation (id) ON DELETE CASCADE,
    epoch BIGINT NOT NULL,
    committer_id UUID NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    commit_data BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (conversation_id, epoch)
);

CREATE TABLE mls_message (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversation (id) ON DELETE CASCADE,
    epoch BIGINT NOT NULL,
    sender_id UUID NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('commit', 'application')),
    ciphertext BYTEA NOT NULL,
    idempotency_key VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (conversation_id, sender_id, idempotency_key)
);

CREATE INDEX mls_message_conversation_epoch_idx ON mls_message (conversation_id, epoch, created_at);

CREATE TABLE mls_recovery_package (
    conversation_id UUID NOT NULL REFERENCES conversation (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    encrypted_package BYTEA NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (conversation_id, user_id)
);

ALTER TABLE message_outbox DROP CONSTRAINT IF EXISTS message_outbox_event_type_check;
ALTER TABLE message_outbox ADD CONSTRAINT message_outbox_event_type_check
    CHECK (event_type IN ('message.created', 'message.deleted', 'message.hidden', 'mls.commit', 'mls.message'));

ALTER TABLE message_outbox ALTER COLUMN message_id DROP NOT NULL;
ALTER TABLE message_outbox ADD COLUMN mls_message_id UUID REFERENCES mls_message (id) ON DELETE CASCADE;
