-- Deleting one account must touch the rows that account owns. PostgreSQL looks up the rows of a
-- referencing table through an index on the foreign key column; without one, `ON DELETE CASCADE`
-- and `ON DELETE SET NULL` both degrade into a sequential scan per deleted row. On the perf stack
-- a fixture reset that deleted 10,000 users ran for over 429 seconds over 659,000 messages, and
-- `AdminService.deleteUser` walks the same path, so an account with a long history was minutes of
-- I/O. Every foreign key below references user_account and had no usable index: either none at
-- all, or one whose leading column is something else, or a partial index that cascade cannot use.

-- The cutover starts from an empty schema, so a plain CREATE INDEX is fine here; on a populated
-- database the same statements would need CREATE INDEX CONCURRENTLY to avoid blocking writes.

-- message.sender_id is the large one: no index existed at all. Without it the planner reads the
-- whole (conversation_id, sender_id, idempotency_key) index and filters it, which is still O(table).
CREATE INDEX message_sender_idx ON message (sender_id);

-- The partial index on conversation_member covers joined members only, but a delete must also
-- remove rows that already left.
CREATE INDEX conversation_member_user_all_idx ON conversation_member (user_id);

CREATE INDEX message_hidden_for_user_user_idx ON message_hidden_for_user (user_id);
CREATE INDEX message_outbox_recipient_idx ON message_outbox (recipient_user_id);

CREATE INDEX mls_message_sender_idx ON mls_message (sender_id);
CREATE INDEX mls_commit_committer_idx ON mls_commit (committer_id);
CREATE INDEX mls_recovery_package_user_idx ON mls_recovery_package (user_id);

CREATE INDEX friendship_user_high_idx ON friendship (user_high_id);
CREATE INDEX friend_request_requester_idx ON friend_request (requester_id);
CREATE INDEX friend_request_pair_high_idx ON friend_request (pair_high_id);
CREATE INDEX user_block_blocked_idx ON user_block (blocked_id);

-- spam_report_one_pending_pair_idx is partial, so resolved reports have no usable index.
CREATE INDEX spam_report_reporter_idx ON spam_report (reporter_id);
CREATE INDEX spam_report_reported_idx ON spam_report (reported_id);
CREATE INDEX spam_report_reviewed_by_idx ON spam_report (reviewed_by);

CREATE INDEX moderation_action_moderator_idx ON moderation_action (moderator_id);

CREATE INDEX conversation_created_by_idx ON conversation (created_by);
CREATE INDEX conversation_direct_high_idx ON conversation (direct_high_id);

CREATE INDEX admin_audit_event_actor_idx ON admin_audit_event (actor_id);
