-- Load-test fixtures: deterministic users, sessions, friendships and one direct
-- conversation per pair, so the k6 script can compute every identifier from an index.
-- Usage: psql -v users=200 -f perf/seed.sql
\set ON_ERROR_STOP on

BEGIN;

-- The perf stack is disposable and every run rewrites the fixtures, so truncate the chat
-- schema instead of deleting users row by row: `message` has no index on its sender, so a
-- per-user delete cascade degrades into a full table scan per user (429 s and counting on a
-- 659k-row table) instead of a constant-time reset.
TRUNCATE chat_v1.user_account CASCADE;

WITH params AS (SELECT :users::int AS n),
     users AS (
         SELECT g,
                ('00000000-0000-4000-8000-' || lpad(g::text, 12, '0'))::uuid AS id,
                'load_' || g AS username,
                'loadtoken-' || lpad(g::text, 6, '0') || '-0123456789abcdef' AS token
         FROM params, generate_series(1, (SELECT n FROM params)) AS g
     )
INSERT INTO chat_v1.user_account (id, username, username_normalized, full_name, email,
                                  email_normalized, address, birth_date, gender, password_hash)
SELECT id, username, username, 'Load User ' || g,
       'load_' || g || '@perf.test', 'load_' || g || '@perf.test',
       'perf', DATE '1990-01-01', 'undisclosed', 'load-test-no-login'
FROM users;

WITH params AS (SELECT :users::int AS n)
INSERT INTO chat_v1.user_session (session_hash, user_id, expires_at)
SELECT encode(sha256(convert_to('loadtoken-' || lpad(g::text, 6, '0') || '-0123456789abcdef', 'UTF8')), 'hex'),
       ('00000000-0000-4000-8000-' || lpad(g::text, 12, '0'))::uuid,
       CURRENT_TIMESTAMP + INTERVAL '12 hours'
FROM params, generate_series(1, (SELECT n FROM params)) AS g;

WITH params AS (SELECT :users::int AS n)
INSERT INTO chat_v1.friendship (user_low_id, user_high_id)
SELECT ('00000000-0000-4000-8000-' || lpad(pair::text, 12, '0'))::uuid,
       ('00000000-0000-4000-8000-' || lpad((pair + 1)::text, 12, '0'))::uuid
FROM params, generate_series(1, (SELECT n FROM params) / 2) AS pair;

WITH params AS (SELECT :users::int AS n)
INSERT INTO chat_v1.conversation (id, type, created_by, direct_low_id, direct_high_id)
SELECT ('00000000-0000-4000-9000-' || lpad(pair::text, 12, '0'))::uuid, 'direct',
       ('00000000-0000-4000-8000-' || lpad((pair * 2 - 1)::text, 12, '0'))::uuid,
       ('00000000-0000-4000-8000-' || lpad((pair * 2 - 1)::text, 12, '0'))::uuid,
       ('00000000-0000-4000-8000-' || lpad((pair * 2)::text, 12, '0'))::uuid
FROM params, generate_series(1, (SELECT n FROM params) / 2) AS pair;

WITH params AS (SELECT :users::int AS n)
INSERT INTO chat_v1.conversation_member (conversation_id, user_id)
SELECT ('00000000-0000-4000-9000-' || lpad(pair::text, 12, '0'))::uuid,
       ('00000000-0000-4000-8000-' || lpad(member::text, 12, '0'))::uuid
FROM params, generate_series(1, (SELECT n FROM params) / 2) AS pair,
     LATERAL (VALUES (pair * 2 - 1), (pair * 2)) AS m(member);

COMMIT;

SELECT (SELECT count(*) FROM chat_v1.user_account WHERE username LIKE 'load\_%') AS users,
       (SELECT count(*) FROM chat_v1.conversation WHERE id::text LIKE '00000000-0000-4000-9000-%') AS conversations;
