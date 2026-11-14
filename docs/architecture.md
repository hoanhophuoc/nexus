# Architecture

Nexus is a modular Gradle build (`settings.gradle`) with one server, two desktop clients, a
shared client runtime and a generated SDK. The server owns all state; the clients are thin,
apart from encryption, which happens on the client.

## Modules

| Module | Responsibility | Depends on |
| --- | --- | --- |
| `server` | REST API, WebSocket, persistence, presence, encryption key packages, moderation | PostgreSQL, Redis, SMTP (optional) |
| `desktop-shared` | Session and encrypted local cache, history sync, MLS client crypto, shared UI kit | `client-sdk` |
| `desktop-user` | End-user application: chats, groups, friends, profile, reports | `desktop-shared` |
| `desktop-admin` | Administrator application: accounts, groups, reports, analytics, audit log | `desktop-shared` |
| `client-sdk` | Generated API client, produced from `api/v1/openapi.yaml` at build time | — |

The server is layered as controller → service → repository. Controllers validate and map
transport, services own transactions and authorization decisions, repositories hold SQL.
SQL is written as text in `Jdbc*` repositories; there is no ORM.

## Runtime topology

```
desktop-user / desktop-admin ── HTTPS + WebSocket ──> server ──> PostgreSQL (schema nexus)
        │                                              └────> Redis (presence, rate limits, fan-out)
        └── local encrypted cache (SQLite/file, key in OS keystore)
```

The server is stateless apart from Redis and the database, so it can be scaled horizontally
behind a load balancer that keeps WebSocket connections sticky or upgrades them directly.

## Data model

Flyway owns the schema (`server/src/main/resources/db/migration`, forward-only, currently
`V1`–`V10`). All objects live in schema `nexus`.

| Area | Tables |
| --- | --- |
| Identity | `user_account`, `user_session`, `password_reset_token`, `login_history` |
| Social | `friendship`, `friend_request`, `user_block`, `spam_report`, `spam_report_evidence` |
| Conversations | `conversation`, `conversation_member`, `message`, `message_hidden_for_user` |
| Delivery | `message_outbox` |
| Encryption | `mls_group_state`, `mls_key_package`, `mls_message`, `mls_commit` |
| Administration | `moderation_action`, `admin_audit_event` |

Rules that the schema enforces rather than the code:

- Usernames and emails are unique on their normalized form; a normalized column is always
  `lower(value)`, so search can target it directly.
- A friendship or friend request is stored once per pair with `user_low_id < user_high_id`.
- `conversation.member_count` and `message.conversation_sequence` are maintained inside the
  same transaction that appends a message, so ordering never depends on wall-clock time.
- Every foreign key that a cascade walks has a leading index (see `V7`, `V8`, `V10`).
- A message delete cascades to its outbox events, hidden markers and MLS payloads.

## Message path

1. `POST /api/v1/conversations/{id}/messages` validates the body (content ≤ 10,000 characters),
   checks membership and a per-user rate limit in Redis, then in one transaction appends the
   message with the next `conversation_sequence` and an idempotency key, and inserts an outbox
   event.
2. The outbox publisher polls pending events every 100 ms through a partial index (`FOR UPDATE OF o
   SKIP LOCKED`, so a second publisher skips the rows a first one holds instead of waiting for
   them, and only rows whose backoff has elapsed), fans them out to connected sessions, and marks
   them published. A batch whose Redis publish fails is rolled
   back and its rows' `attempts` counter is incremented in a separate transaction, so the failed
   events stay pending for the next poll; that batch also increments `nexus.outbox.publish_failures`
   and sets `nexus.outbox.max_attempts` from the counts it just wrote. Ages come from the database
   (`clock_timestamp() - created_at`) rather than from the application clock, so a drifting host
   cannot falsify the latency alarm. Delivery is at-least-once —
   a failed mark after a successful publish makes the next poll send the same event again, which
   `OutboxPublisherIntegrationTest` demonstrates — and clients deduplicate on message id and sequence.

   A row is polled until it has failed `MAX_ATTEMPTS` (5) publishes: both pending polls filter on
   `attempts < 5`, so an event that keeps failing stops costing a Redis round trip every 100 ms. The
   batch that pushes a row over the line logs it at WARN — the first three event ids and a count — and
   increments `nexus.outbox.abandoned` with `reason=retries`; the row stays in `message_outbox`
   unpublished, with `abandoned_at`, `abandon_reason` and `last_error` as the record of what was not
   delivered and why, and `nexus.outbox.max_attempts` carries that high-water mark until a batch
   publishes again, when it returns to 0 so the alert clears.    A row that cannot be encoded is abandoned the same way without failing the rest of its batch,
   because no number of retries can publish it, and its counter carries `reason=encode`. A payload
   Redis rejects on its own is isolated too: when a batch publish fails for a reason other than an
   unreachable connection, the rows are sent one at a time, the ones that go out are marked
   published, and only the rejected row counts an attempt. A failed publish is not retried on the next tick: the
   retry counter also moves `next_attempt_at` out by 2, 4, 8 and then 16 seconds, and both polls only
   take rows whose time has come, so a row rides out roughly half a minute of Redis trouble before the
   cap abandons it. There is no dead-letter table, because the unpublished row already is one —
   `docs/operations.md` has the query that lists those rows and the way to requeue them once Redis is
   healthy again.
3. The receiving client appends the message to its encrypted local cache and acknowledges it
   over the WebSocket. Acknowledgements are what make the "delivered" state appear for the
   sender.

The outbox is the reason a crash between commit and fan-out loses nothing: the event is
already durable.

## Presence

Presence is Redis-only, never a database row. A heartbeat writes a key with a 90 s TTL; a
session is online if its key exists. Reads are batched per page, so rendering a friend list
costs one round trip rather than one per user. Presence is intentionally ephemeral: a Redis
flush shows everyone as offline until their next heartbeat, and nothing else is affected.

## Authentication and authorization

- A session is an opaque token in an `HttpOnly`, `Secure` (configurable) cookie; the server
  stores only its hash. `GET /api/v1/auth/csrf` issues the CSRF token that state-changing
  requests must send.
- Passwords are hashed with a memory-hard function; login, registration and password reset are
  rate limited per account and per address in Redis.
- Every read and write is scoped to the authenticated user id from the session. Identifiers in
  a request are never trusted as proof of ownership: membership, friendship and admin role are
  re-checked in SQL.
- Administrator endpoints require the `admin` role at the controller boundary and again in the
  service, and every mutation writes an `admin_audit_event` row in the same transaction.

## End-to-end encryption for groups

Direct chats rely on transport security and the server's authorization; group chats add MLS on
top: each member publishes a key package, the client maintains the group state locally, and the
server stores opaque MLS messages, commits and recovery packages. The server never receives
plaintext or group secrets, so it can deliver history but cannot read group content. Rotating
a member's keys is a client operation that produces a commit; the server only orders and
delivers it.

## Limits that shape the design

| Limit | Value | Why |
| --- | --- | --- |
| WebSocket connections | `NEXUS_MAX_CONNECTIONS` (default 20,000) | Tomcat accepts 8,192 by default; the measured ceiling is the connection cap, not CPU |
| Message body | 10,000 characters | Keeps one row and one frame bounded |
| Page size | 1–100, default 25 | Bounds every list response and its presence lookup |
| Report evidence | 10 items × 4,000 characters | Bounds moderation payloads |
| Presence TTL | 90 s, heartbeat 30 s | Three missed beats mark a session offline |
| Outbox poll | 100 ms | Delivery latency budget without busy waiting |
