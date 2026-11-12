# API

The contract is `api/v1/openapi.yaml`. It is validated in the build
(`:client-sdk:openApiValidate`) and `client-sdk` is generated from it, so the document and the
generated client cannot drift silently. The server implements that contract; when the two
disagree, the OpenAPI file is the reference and the server is the bug.

## Base URL and transport

- REST base: `/api/v1` on the server origin (default `http://localhost:8080`).
- WebSocket: `/ws/v1` on the same origin, upgraded from HTTP, one connection per signed-in
  client.
- All state-changing REST calls are JSON. Sessions are cookies, so browsers and the clients
  must call the CSRF endpoint first (below).

## Authentication

1. `GET /api/v1/auth/csrf` returns a CSRF token and sets the session cookie. Send the token in
   the `X-XSRF-TOKEN` header on every `POST`, `PUT` and `DELETE`.
2. `POST /api/v1/auth/login` with `username` and `password` creates the session. The cookie is
   `HttpOnly` and `Secure` unless `NEXUS_SECURE_COOKIE=false` for local HTTP development.
3. `POST /api/v1/auth/logout` revokes it. `PUT /api/v1/auth/password` changes the password of the
   signed-in account.
4. `POST /api/v1/auth/password-reset` and `POST /api/v1/auth/password-reset/confirm` implement
   the email flow. They are rate limited and always answer the same way for unknown addresses.

The WebSocket handshake authenticates from the same session cookie; there is no token in the
URL and no unauthenticated socket.

## Errors

Failures use `application/problem+json` (`ProblemDetail`): `status`, `title`, `detail`. The
status codes are the meaningful part:

| Status | Meaning |
| --- | --- |
| 400 | The request is malformed or fails validation |
| 401 | No session, or the session expired |
| 403 | Authenticated but not allowed (not a member, not an admin) |
| 404 | The object does not exist or is not visible to this account |
| 409 | The change conflicts with current state (duplicate account, duplicate friend request) |
| 429 | Rate limited; retry after the window |

## Pagination

List endpoints take `limit` (1–100, default 25 or 50 depending on the endpoint) and an opaque
`cursor`. Responses carry `items` and `nextCursor`; pass `nextCursor` back unchanged and stop
when it is `null`. Cursors are stable under insertion because they encode the sort key and id
of the last item, not an offset.

## Endpoints by area

| Area | Endpoints |
| --- | --- |
| Authentication | `/auth/csrf`, `/auth/register`, `/auth/login`, `/auth/logout`, `/auth/password`, `/auth/password-reset`, `/auth/password-reset/confirm` |
| Profile and keys | `/users/me`, `/users/me/key-package`, `/users/{userId}/key-package` |
| Discovery | `/users` (substring search over username and full name, case-insensitive, wildcards matched literally) |
| Friends | `/friends`, `/friends/{userId}`, `/friend-requests`, `/friend-requests/outgoing`, `/friend-requests/{requestId}` |
| Blocks and reports | `/blocks`, `/blocks/{userId}`, `/reports` |
| Conversations | `/conversations`, `/conversations/{conversationId}/messages`, `/conversations/{conversationId}/messages/{messageId}`, `/conversations/{conversationId}/history` |
| Groups | `/groups`, `/groups/{groupId}`, `/groups/{groupId}/members`, `/groups/{groupId}/members/{userId}`, `/groups/{groupId}/members/{userId}/role`, `/groups/{groupId}/mls-messages`, `/groups/{groupId}/mls-commits`, `/groups/{groupId}/recovery-package` |
| Administration | `/admin/users`, `/admin/users/{userId}`, `/admin/users/{userId}/lock`, `/admin/users/{userId}/password-reset`, `/admin/users/{userId}/login-history`, `/admin/login-history`, `/admin/users/{userId}/friends`, `/admin/registrations`, `/admin/groups`, `/admin/groups/{groupId}/members`, `/admin/reports`, `/admin/reports/{reportId}/decision`, `/admin/analytics/*`, `/admin/audit-events` |

Administrator endpoints require the `admin` role and every mutation writes an audit event in the
same transaction.

## WebSocket frames

The socket carries server-initiated events; REST carries the commands. Every frame is JSON with
a `type` field:

| `type` | Meaning |
| --- | --- |
| `MessageCreated` | A message became durable in a conversation the session belongs to |
| `MessageDeleted` | A message was deleted for everyone |
| `MlsMessage` | An opaque MLS application message for a group |
| `MlsCommit` | An opaque MLS commit that advances the group state |

Clients acknowledge delivery over the same socket. The server deduplicates by message id, and
reconnecting clients reconcile from the last sequence they hold, so a missed frame is repaired
by a REST fetch rather than by replaying the socket.

## Generated client

`client-sdk` is generated with OpenAPI Generator (Java, native HTTP library, Jackson 3) into
package `com.nexus.sdk`. Regenerate with `./gradlew :client-sdk:openApiGenerate`; the generated
sources live under `client-sdk/build/generated/openapi` and are not committed. The desktop
modules consume the built jar, so a contract change is visible to them at compile time.
