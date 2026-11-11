-- The outbox is written once per message and read by the publisher through its pending index, but
-- its own foreign keys had no index at all. Deleting one message then cost a sequential scan of the
-- whole outbox table to find the events that referenced it: 20.96 ms of trigger time for a single
-- row against 400k events, measured with EXPLAIN (ANALYZE) on the perf database. Deleting a
-- conversation or an account cascades through many messages, so that cost multiplies per row.
--
-- V7 covered the foreign keys that reference user_account; these reference conversation, message
-- and mls_message, which V7 did not touch.

CREATE INDEX message_outbox_message_idx ON message_outbox (message_id);
CREATE INDEX message_outbox_conversation_idx ON message_outbox (conversation_id);
CREATE INDEX message_outbox_mls_message_idx ON message_outbox (mls_message_id);

-- conversation_direct_pair_idx is partial (type = 'direct'), and a foreign key cascade cannot use a
-- partial index, so deleting a direct conversation's participant still scanned the table.
CREATE INDEX conversation_direct_low_idx ON conversation (direct_low_id);
