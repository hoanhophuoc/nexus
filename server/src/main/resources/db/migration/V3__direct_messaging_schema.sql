CREATE TABLE conversation (
    id UUID PRIMARY KEY,
    type VARCHAR(16) NOT NULL CHECK (type IN ('direct', 'group')),
    name VARCHAR(120),
    created_by UUID REFERENCES user_account (id) ON DELETE SET NULL,
    direct_low_id UUID REFERENCES user_account (id) ON DELETE CASCADE,
    direct_high_id UUID REFERENCES user_account (id) ON DELETE CASCADE,
    next_message_sequence BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((type = 'direct' AND direct_low_id IS NOT NULL AND direct_high_id IS NOT NULL
            AND direct_low_id < direct_high_id AND name IS NULL)
        OR (type = 'group' AND direct_low_id IS NULL AND direct_high_id IS NULL))
);

CREATE UNIQUE INDEX conversation_direct_pair_idx ON conversation (direct_low_id, direct_high_id)
    WHERE type = 'direct';

CREATE TABLE conversation_member (
    conversation_id UUID NOT NULL REFERENCES conversation (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL DEFAULT 'member' CHECK (role IN ('admin', 'member')),
    joined_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    left_at TIMESTAMPTZ,
    history_cleared_sequence BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (conversation_id, user_id)
);

CREATE INDEX conversation_member_user_idx ON conversation_member (user_id, conversation_id)
    WHERE left_at IS NULL;

CREATE TABLE message (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversation (id) ON DELETE CASCADE,
    sender_id UUID NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    conversation_sequence BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    content VARCHAR(10000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    deleted_for_everyone_at TIMESTAMPTZ,
    UNIQUE (conversation_id, sender_id, idempotency_key),
    UNIQUE (conversation_id, conversation_sequence)
);

CREATE INDEX message_conversation_cursor_idx ON message (conversation_id, conversation_sequence);

CREATE TABLE message_hidden_for_user (
    message_id UUID NOT NULL REFERENCES message (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    hidden_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (message_id, user_id)
);

CREATE TABLE message_outbox (
    event_id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversation (id) ON DELETE CASCADE,
    message_id UUID NOT NULL REFERENCES message (id) ON DELETE CASCADE,
    recipient_user_id UUID REFERENCES user_account (id) ON DELETE CASCADE,
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN ('message.created', 'message.deleted', 'message.hidden')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX message_outbox_pending_idx ON message_outbox (created_at, event_id) WHERE published_at IS NULL;
