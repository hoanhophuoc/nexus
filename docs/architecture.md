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
2. The outbox publisher polls pending events every 100 ms through a partial index, fans them out
   to connected sessions, and marks them published. Delivery is at-least-once; clients
   deduplicate on message id and sequence.
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
