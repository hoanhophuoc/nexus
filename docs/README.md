# Nexus documentation

This directory is the single source of truth for how Nexus is built, configured, operated
and measured. `README.md` at the repository root is only a landing page.

## The documents

| Document | Answers |
| --- | --- |
| [architecture.md](architecture.md) | What the pieces are, how a message travels, where data lives, how encryption and authorization work |
| [api.md](api.md) | How to call the REST API and the WebSocket, how authentication and pagination work |
| [development.md](development.md) | How to build, run and test; coding conventions; how to add a migration |
| [operations.md](operations.md) | Every environment variable, deployment topology, health and metrics, backups, scaling knobs |
| [ci-cd.md](ci-cd.md) | What each workflow does, which gates can fail a build, how a release is cut |
| [performance.md](performance.md) | Measured capacity, latency, and the database cost of each hot path |
| [ui-ux-design-system.md](ui-ux-design-system.md) | Canonical native JavaFX visual language, component contracts, interaction, motion, accessibility and UI quality gates |
| [ui-ux-tooling.md](ui-ux-tooling.md) | Evaluated design/JavaFX libraries, agent skills, adoption decisions, compatibility and dependency gates |


## One name

The project is **Nexus** everywhere, and only that:

| Surface | Value |
| --- | --- |
| Java package | `com.nexus` (`com.nexus.server`, `com.nexus.desktop.*`, `com.nexus.sdk`) |
| Gradle root project | `Nexus`, group `com.nexus` |
| PostgreSQL | database `nexus`, schema `nexus`, default role `nexus` |
| Environment variables | `NEXUS_*` |
| Spring configuration keys | `nexus.*` |
| Compose projects and containers | `nexus`, `nexus-perf`, services `db`, `redis`, `server`, `prometheus`, `k6` |
| Server jar and image | `nexus-server.jar`, image built by `Dockerfile` |
| Metrics application tag | `nexus-server` |

When a change touches one of these surfaces, update this table and every document that
mentions it in the same commit.

## Documentation rules

1. **One home per fact.** A fact is written once; other documents link to it. Environment
   variables are listed only in `operations.md`, gates only in `ci-cd.md`, measurements only
   in `performance.md`.
2. **Code is the authority.** When code and a document disagree, the document is wrong. Fix it
   in the same change that alters the code — a migration, a new endpoint, a renamed variable or
   a changed limit.
3. **No history.** Documents describe the current system. Older designs, superseded plans and
   meeting notes do not belong here; git history holds them.
4. **Facts carry evidence.** Numbers in `performance.md` name the command or fixture that
   produced them, so anyone can repeat the measurement.
5. **Nothing unverifiable.** If a claim cannot be checked against the repository or a run of
   the test suite, it is removed rather than softened.

## Updating this set

| If you change… | Update… |
| --- | --- |
| a table, column, constraint or index | `architecture.md` (data model), `development.md` (migration rules) |
| an endpoint, payload, error or WebSocket frame | `api.md` and `api/v1/openapi.yaml` |
| a Gradle task, test suite or toolchain version | `development.md`, `ci-cd.md` |
| an environment variable, port, health check or resource limit | `operations.md` |
| a workflow, gate or release step | `ci-cd.md` |
| a hot-path query, index or measured number | `performance.md` |
| the project name on any surface above | this file, then everything it lists |
