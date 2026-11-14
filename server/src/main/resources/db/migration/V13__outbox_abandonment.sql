-- A row that is given up on stays in the table as the record of what was not delivered, so it needs
-- to say when it was given up on, why (a payload that cannot be encoded never gets better; a row
-- that failed five publishes might) and the last error that stopped it. Requeueing clears all four
-- columns so the row is polled again with a clean history.
ALTER TABLE message_outbox
    ADD COLUMN abandoned_at TIMESTAMPTZ,
    ADD COLUMN abandon_reason VARCHAR(16)
        CHECK (abandon_reason IN ('encode', 'retries')),
    ADD COLUMN last_error TEXT;

-- Rows given up on before this migration never recorded when or why; backfill them so the admin
-- command can list and requeue them too.
UPDATE message_outbox
SET abandoned_at = next_attempt_at, abandon_reason = 'retries',
    last_error = 'abandoned before the reason was recorded'
WHERE published_at IS NULL AND attempts >= 5 AND abandoned_at IS NULL;

-- The admin command lists abandoned rows; published and pending rows are the overwhelming majority.
CREATE INDEX message_outbox_abandoned_idx ON message_outbox (abandoned_at)
    WHERE abandoned_at IS NOT NULL;
