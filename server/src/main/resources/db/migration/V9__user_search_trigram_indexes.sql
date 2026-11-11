-- User search matches a substring of the username or the full name, and the predicate used to be
-- POSITION(...) > 0, which no index can serve: a term that matched nobody made the planner walk the
-- whole user_account table in id order, 6.6 ms for 20,000 rows and linear in the number of users.
--
-- pg_trgm understands substring LIKE, so the query now uses LIKE with escaped wildcards against
-- these two trigram indexes. Measured on the perf database: a term nobody has plans as a BitmapOr of
-- both indexes in 0.127 ms instead of a full traversal.
--
-- fastupdate is off because the default pending list defeats the index exactly where it is needed
-- first: with the indexes built on an empty table and then filled by the usual sign-up traffic, the
-- 20,000 rows stayed in the pending list and the planner went back to a sequential scan (4.7 ms)
-- until something vacuumed the table. Without the pending list the same query uses both indexes
-- immediately, whether the index was built on existing rows or grown from empty. The write cost is
-- paid on account creation only.
--
-- The extension is installed in public and the operator class is schema-qualified, because Flyway
-- runs with the application schema on its search_path and the opclass must resolve regardless.

CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

CREATE INDEX user_account_username_trgm_idx
    ON user_account USING gin (username_normalized public.gin_trgm_ops) WITH (fastupdate = off);

CREATE INDEX user_account_full_name_trgm_idx
    ON user_account USING gin (LOWER(full_name) public.gin_trgm_ops) WITH (fastupdate = off);
