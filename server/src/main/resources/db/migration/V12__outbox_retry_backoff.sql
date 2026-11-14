-- A failed publish used to be retried on the next 100 ms tick, so five attempts abandoned a row after
-- roughly half a second of Redis trouble. Rows now carry the earliest time they may be polled again.
ALTER TABLE message_outbox
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp();
