-- Align report status with the public contract and keep audit/moderation records
-- when an administrator account is deleted.

ALTER TABLE spam_report DROP CONSTRAINT spam_report_status_check;
UPDATE spam_report SET status = 'upheld' WHERE status = 'reviewed';
ALTER TABLE spam_report ADD CONSTRAINT spam_report_status_check
    CHECK (status IN ('pending', 'upheld', 'dismissed'));

ALTER TABLE spam_report DROP CONSTRAINT spam_report_reviewed_by_fkey;
ALTER TABLE spam_report ADD CONSTRAINT spam_report_reviewed_by_fkey
    FOREIGN KEY (reviewed_by) REFERENCES user_account (id) ON DELETE SET NULL;

ALTER TABLE admin_audit_event DROP CONSTRAINT admin_audit_event_actor_id_fkey;
ALTER TABLE admin_audit_event ALTER COLUMN actor_id DROP NOT NULL;
ALTER TABLE admin_audit_event ADD CONSTRAINT admin_audit_event_actor_id_fkey
    FOREIGN KEY (actor_id) REFERENCES user_account (id) ON DELETE SET NULL;

ALTER TABLE moderation_action DROP CONSTRAINT moderation_action_moderator_id_fkey;
ALTER TABLE moderation_action ALTER COLUMN moderator_id DROP NOT NULL;
ALTER TABLE moderation_action ADD CONSTRAINT moderation_action_moderator_id_fkey
    FOREIGN KEY (moderator_id) REFERENCES user_account (id) ON DELETE SET NULL;
