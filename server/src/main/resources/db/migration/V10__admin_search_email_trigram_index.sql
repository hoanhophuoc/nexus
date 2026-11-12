-- The admin account list filters on username, full name and email. V9 already indexed the first two
-- through user_account_username_trgm_idx and user_account_full_name_trgm_idx, which the filter now
-- uses because it searches username_normalized and lower(full_name); email had no index at all, so a
-- term that matched nobody still walked the table. Same shape as V9, including fastupdate off so the
-- index is usable as soon as accounts are created rather than after the first vacuum.

CREATE INDEX user_account_email_trgm_idx
    ON user_account USING gin (email_normalized public.gin_trgm_ops) WITH (fastupdate = off);
