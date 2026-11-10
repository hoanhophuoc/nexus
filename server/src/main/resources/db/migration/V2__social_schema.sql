CREATE TABLE friendship (
    user_low_id UUID NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    user_high_id UUID NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_low_id, user_high_id),
    CHECK (user_low_id < user_high_id)
);

CREATE TABLE friend_request (
    id UUID PRIMARY KEY,
    requester_id UUID NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    target_id UUID NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    pair_low_id UUID NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    pair_high_id UUID NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (pair_low_id, pair_high_id),
    CHECK (requester_id <> target_id),
    CHECK (pair_low_id < pair_high_id)
);

CREATE INDEX friend_request_target_idx ON friend_request (target_id, id);

CREATE TABLE user_block (
    blocker_id UUID NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    blocked_id UUID NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (blocker_id, blocked_id),
    CHECK (blocker_id <> blocked_id)
);

CREATE TABLE spam_report (
    id UUID PRIMARY KEY,
    reporter_id UUID NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    reported_id UUID NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    reason VARCHAR(2000) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'reviewed', 'dismissed')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_by UUID REFERENCES user_account (id),
    reviewed_at TIMESTAMPTZ,
    CHECK (reporter_id <> reported_id)
);

CREATE UNIQUE INDEX spam_report_one_pending_pair_idx ON spam_report (reporter_id, reported_id)
    WHERE status = 'pending';
CREATE INDEX spam_report_status_time_idx ON spam_report (status, created_at DESC);

CREATE TABLE spam_report_evidence (
    report_id UUID NOT NULL REFERENCES spam_report (id) ON DELETE CASCADE,
    message_id UUID NOT NULL,
    submitted_content VARCHAR(4000) NOT NULL,
    PRIMARY KEY (report_id, message_id)
);

CREATE TABLE moderation_action (
    id UUID PRIMARY KEY,
    moderator_id UUID NOT NULL REFERENCES user_account (id),
    report_id UUID REFERENCES spam_report (id),
    target_user_id UUID NOT NULL REFERENCES user_account (id),
    action VARCHAR(32) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX moderation_action_target_time_idx ON moderation_action (target_user_id, created_at DESC);
