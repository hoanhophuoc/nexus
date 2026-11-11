# **Real-Time Chat System with JavaFX**

[![CI](https://github.com/hoanhophuoc/new-chat-system/actions/workflows/ci.yml/badge.svg)](https://github.com/hoanhophuoc/new-chat-system/actions/workflows/ci.yml)
[![CodeQL](https://github.com/hoanhophuoc/new-chat-system/actions/workflows/codeql.yml/badge.svg)](https://github.com/hoanhophuoc/new-chat-system/actions/workflows/codeql.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=hoanhophuoc_Chat-System&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=hoanhophuoc_Chat-System)

The CI/CD and release pipeline is documented in [docs/CI-CD.md](docs/CI-CD.md).

## **Introduction**

Welcome to the **Real-Time Chat System**! This project is a desktop-based chat application built with **JavaFX** for the user interface and **Java asynchronous sockets** for real-time communication. It ships with a user client, an admin client and a socket server backed by PostgreSQL.

---

## **Features**

### **Admin Panel Features**
1. **User Management:**
   - View, add, update, and delete user accounts.
   - Lock user accounts and reset passwords.
   - View login history and friend lists.

2. **Group Chat Management:**
   - Manage chat groups, including viewing members and admins.
   - Sort and filter groups by name or creation time.

3. **Spam Reports:**
   - View, sort, and filter spam reports.
   - Ban users based on spam activity.

4. **User Statistics:**
   - Monitor active users and display visual reports.
   - View the number of new registrations per month with graphs.

---

### **User Features**
1. **Account Management:**
   - Register, log in, update profile information, and reset passwords.

2. **Friend Management:**
   - Send, accept, and decline friend requests.
   - Block users, view online friends, and filter by name.

3. **Real-Time Messaging:**
   - Instant message delivery with real-time updates.
   - View chat history, search within conversations, and delete messages.

4. **Group Chat:**
   - Create and manage groups with admin privileges.

---

## **Technologies Used**

| Technology | Version | Purpose |
|---|---|---|
| Java | **27** | Language/runtime (provisioned by the Gradle toolchain) |
| JavaFX | **27.0.1** | Graphical user interface |
| Gradle | **9.9.0** | Build, dependencies and packaging |
| Hibernate ORM | **7.4.14.Final** | Persistence (Jakarta Persistence 3.2) |
| PostgreSQL | **18** (Docker image) | Database |
| PostgreSQL JDBC | **42.7.14** | Database driver |
| HikariCP | **7.1.0** | Connection pool |
| Jackson | **3.3.0** (`tools.jackson`) | JSON messages between client and server |
| Jakarta Mail + Angus Mail | **2.1.5 / 2.0.5** | Password-reset email |
| SLF4J | **2.0.20** | Logging |
| JUnit Jupiter | **6.1.3** | Test framework |
| Password4j | **1.8.4** | Argon2id password hashing (with BCrypt verification for old hashes) |

Asynchronous `AsynchronousSocketChannel` handles all client/server communication, with one virtual thread per connection.

---

## **Architecture**

```
┌──────────────┐   ┌──────────────┐        ┌───────────────────┐        ┌──────────────┐
│  User client │   │ Admin client │  TCP   │   Chat server     │  JDBC  │  PostgreSQL  │
│   (JavaFX)   │   │   (JavaFX)   │ ─────► │ (async sockets)   │ ─────► │      18      │
└──────────────┘   └──────────────┘ :8080  └───────────────────┘        └──────────────┘
```

The desktop clients run on your machine; the server and database run either in Docker (recommended) or locally.

---

## **Requirements**

- **Docker Engine 24+** with **Docker Compose v2** — for the containerized server and database.
- **JDK 27** (or newer) — only needed to build/run the desktop clients or the server directly. The Gradle wrapper downloads the configured JDK 27 toolchain automatically if it is missing.
- A desktop environment — the two clients are JavaFX GUI applications.

---

## **Quick Start with Docker**

### 1. Create your environment file

```bash
cp .env.example .env
```

Edit `.env` and set a real `POSTGRES_PASSWORD` (and the optional mail credentials if you want password-reset emails). Compose passes that same password to the server container as `CHAT_DB_PASSWORD`.

### 2. Start the stack

```bash
docker compose up --build -d
```

This builds the server image, starts PostgreSQL 18, and the server waits for the database health check before starting. On the first run Hibernate creates all tables (`CHAT_DB_DDL_AUTO=update`).

Check status and logs:

```bash
docker compose ps
docker compose logs -f server
```

The server is reachable at `localhost:8080` and should log:

```
Server started at 0.0.0.0:8080
```

### 3. Run the desktop clients

The clients are GUI applications, so they run on your desktop rather than in a container:

```bash
./gradlew runClient    # user client
./gradlew runAdmin     # admin client
```

Both read `client.host=localhost` / `client.port=8080` by default and connect to the Dockerized server. To connect to a server elsewhere:

```bash
CHAT_CLIENT_HOST=192.168.1.20 CHAT_CLIENT_PORT=8080 ./gradlew runClient
```

You can also place a `client-config.properties` file in the working directory with `client.host` / `client.port`.

### 4. Create an admin account (optional)

Registration from the user client always creates a normal user. To log into the admin panel, insert an admin once (the example hash below is BCrypt for the password `admin123` — change it after the first login):

```bash
docker compose exec -T db psql -U chat -d chat_system <<'SQL'
INSERT INTO customer (username, password, name, email, create_date, is_lock, is_admin)
VALUES ('admin', '$2a$10$OpmVhM0U6OBNC6kmfqchGuW0Ypkx.oz5Q8pJ6RSGmZ/t/7Ox9a/DW',
        'Administrator', 'admin@example.com', NOW(), false, true);
SQL
```

### Stop and reset

```bash
docker compose down       # stop containers, keep the database volume
docker compose down -v    # stop and delete the database volume (fresh start)
```

---

## **Configuration**

Configuration is layered; later sources win:

1. Defaults packaged in `src/main/resources/server-config.properties` (and `client-config.properties`).
2. An optional file with the same name in the working directory (e.g. `./server-config.properties`).
3. Environment variables.

> `.env` is read **only by Docker Compose**. A server started from an IDE or the command line reads process environment variables and an optional `./server-config.properties`; it does not load `.env`.

### Server environment variables

| Variable | Property | Default |
|---|---|---|
| `CHAT_DB_URL` | `hibernate.hikari.dataSource.url` | `jdbc:postgresql://localhost:5432/chat_system` |
| `CHAT_DB_USER` | `hibernate.hikari.dataSource.user` | `chat` |
| `CHAT_DB_PASSWORD` | `hibernate.hikari.dataSource.password` | `chat` |
| `CHAT_DB_DDL_AUTO` | `hibernate.hbm2ddl.auto` | `update` (set `none` for a managed database) |
| `CHAT_SERVER_HOST` | `server.host` | `localhost` (compose sets `0.0.0.0`) |
| `CHAT_SERVER_PORT` | `server.port` | `8080` |
| `CHAT_EMAIL_USERNAME` | `email.username` | empty |
| `CHAT_EMAIL_PASSWORD` | `email.password` | empty |
| `CHAT_MAIL_HEALTH_ENABLED` | `management.health.mail.enabled` | `false` (SMTP is optional) |

### Client environment variables

| Variable | Property | Default |
|---|---|---|
| `CHAT_CLIENT_HOST` | `client.host` | `localhost` |
| `CHAT_CLIENT_PORT` | `client.port` | `8080` |

### Docker Compose variables (`.env`)

| Variable | Description | Default |
|---|---|---|
| `POSTGRES_DB` | Database name | `chat_system` |
| `POSTGRES_USER` | Database user | `chat` |
| `POSTGRES_PASSWORD` | Database password (also passed to the server container as `CHAT_DB_PASSWORD`) | `chat` |
| `CHAT_SERVER_PORT` | Host port for the server | `8080` |
| `CHAT_DB_DDL_AUTO` | Schema management | `update` |
| `CHAT_EMAIL_USERNAME` | Gmail address for reset mails | empty |
| `CHAT_EMAIL_PASSWORD` | Gmail app password | empty |
| `CHAT_MAIL_HEALTH_ENABLED` | Include SMTP in `/actuator/health` | `false` |

If you change `CHAT_SERVER_PORT`, start the clients with the same port via `CHAT_CLIENT_PORT`.

> **Remote/managed databases:** point `CHAT_DB_URL` at your own instance, e.g.
> `jdbc:postgresql://host:5432/defaultdb?ssl=require`, and provide the credentials through the
> environment. The repository no longer contains database or mail credentials.

---

## **Local Development (without Docker)**

1. Make sure a PostgreSQL instance is reachable and the database exists.
2. Point the server at it and let Hibernate create the tables:

```bash
CHAT_DB_URL=jdbc:postgresql://localhost:5432/chat_system \
CHAT_DB_USER=chat \
CHAT_DB_PASSWORD=<POSTGRES_PASSWORD from .env> \
CHAT_DB_DDL_AUTO=update \
./gradlew run
```

If the database runs in Compose, use the `POSTGRES_PASSWORD` value from `.env`. Then start the clients with `./gradlew runClient` or `./gradlew runAdmin`.

### Running the server from IntelliJ IDEA

The Compose database is published on `127.0.0.1:5432`, so the server can run in the IDE while PostgreSQL stays in Docker:

1. Start only the database: `docker compose up -d db`. If the full stack is already running, stop the server service first (`docker compose stop server`), or port 8080 is already taken.
2. In the `ServerApp` run configuration, add this environment variable:

   ```
   CHAT_DB_PASSWORD=<POSTGRES_PASSWORD from .env>
   ```

   The URL and user already match the defaults (`localhost:5432/chat_system`, `chat`), so no other variables are required. IntelliJ does not load `.env`; the value must be in the run configuration (or exported in the shell that starts Gradle).
3. Run `ServerApp`. The Gradle run tasks add `--add-modules ALL-MODULE-PATH`, which Hibernate needs to resolve its JBoss Logging backend, plus `--enable-native-access=javafx.graphics` for JavaFX.

Alternatively, run it from a terminal:

```bash
CHAT_DB_PASSWORD=<POSTGRES_PASSWORD> ./gradlew run
```

The client and admin run configurations do not need database access.

Other useful commands:

```bash
./gradlew clean build     # compile, run tests, build jars and distributions
./gradlew installDist     # creates build/install/Chat-System (used by the Docker image)
./gradlew distZip         # zip distribution of the server
./gradlew test            # fast unit tests
./gradlew integrationTest # PostgreSQL 18 (Testcontainers) and real socket tests
./gradlew fxTest          # JavaFX/FXML tests (needs a display or Xvfb)
./gradlew fullTest        # unit + integration + JavaFX tests
./gradlew jacocoTestReport
./gradlew cyclonedxBom
./gradlew packageClient   # jpackage app image; add -PpackageType=deb for a .deb
./gradlew packageAdmin
```

---

### Live user journey (fully isolated)

The opt-in `RealServerUserJourneyFxTest` creates real accounts, exercises the user and admin flows, and deletes its accounts. Its runner needs **Docker Engine with network access** for image layers and Gradle dependencies: no Compose stack, JDK, display, database, Redis, or pre-existing admin account.

```bash
./scripts/live-journey.sh
```

The script builds a dedicated server image and a test-runner image, creates a private Docker network, starts throwaway PostgreSQL, Redis and server containers, then registers a temporary admin through the real API and promotes it in that throwaway database. It runs the JavaFX journey headlessly inside the test-runner image (a Gradle image with the Pango runtime JavaFX needs for text layout) and forces the test task to run, so a reusable Gradle cache cannot turn it into an up-to-date no-op. It always removes the test/server/Redis/PostgreSQL containers, the private network and its temporary Gradle cache. It never reads or modifies the normal Compose stack or database. The reusable server and test-runner images and Docker build cache remain.

Use `--skip-build` to reuse the current `chat-system-live-server:latest` and `chat-system-live-test:latest` images. Optional `CHAT_REAL_ADMIN_USERNAME` and `CHAT_REAL_ADMIN_PASSWORD` values override the generated temporary admin credentials; both are provisioned inside the throwaway database. `CHAT_LIVE_SERVER_IMAGE`, `CHAT_LIVE_TEST_IMAGE`, `CHAT_LIVE_GRADLE_IMAGE` and `CHAT_LIVE_GRADLE_HOME` override image tags, the test image's Gradle base, and its cache directory. A reused `CHAT_LIVE_GRADLE_HOME` must not be in use by another Gradle build. `./scripts/live-journey.sh --help` lists all options.

---

## **Project Layout**

```
src/main/java/com/haichutieu/chatsystem/
├── client/          JavaFX clients (ClientApp, AdminApp) and socket client
│   ├── bus/         Controllers between GUI and socket layer
│   ├── gui/         FXML views, including gui/adminPanel
│   └── util/        Scene/session helpers
├── dto/             JPA entities and data-transfer objects
├── server/          Socket server entry point and async server
│   └── dal/         Hibernate services
└── util/            Config loader and JSON helpers
src/main/resources/
├── com/haichutieu/chatsystem/client/gui/   FXML and images
├── com/haichutieu/chatsystem/client/stylesheets/  CSS used by both clients
├── client-config.properties                Client defaults
├── server-config.properties                Server/DB defaults
└── hibernate.cfg.xml                       Entity mappings
Dockerfile             Multi-stage build (Gradle → JRE)
docker-compose.yml     PostgreSQL 18 + server
.env.example           Template for compose settings
```

---

## **Password Storage**

New passwords are hashed with **Argon2id** (Password4j; 32 MiB memory, 3 iterations, 1 lane, 32-byte output).
Hashes created by earlier releases (BCrypt, `$2a$...`) still verify and are upgraded to Argon2id automatically on the next successful login, so no user action is required.

Argon2id hashes are longer than BCrypt's 60 characters, so the `password` column is `VARCHAR(255)` for fresh databases. Existing databases need a one-time widening (Hibernate's `update` mode usually does this on startup; run it explicitly if it does not):

```sql
ALTER TABLE customer ALTER COLUMN password TYPE varchar(255);
```

The admin account created with the BCrypt hash in the quick start is verified as-is and upgraded on its first login.

---

## **Troubleshooting**

- **`Address already in use` on startup** — another server owns port 8080. Stop the Compose server (`docker compose stop server`) before running `ServerApp` in the IDE, or run only the IDE server and leave the Compose `server` service stopped. To switch back, stop the IDE server and run `docker compose up -d server`.
- **`password authentication failed for user "chat"`** — the app is not using the Compose password. Set `CHAT_DB_PASSWORD` to the `POSTGRES_PASSWORD` value from `.env`; Java never reads `.env` itself. If the Compose server fails instead, the database volume may predate a `.env` password change: reset it with `docker compose down -v && docker compose up -d`.
- **Console output is red but there are no failures** — IntelliJ renders anything written to stderr in red. Hibernate's `java.util.logging` and HikariCP's SLF4J output go to stderr by default, so normal `INFO` startup lines are red. Only stack traces indicate errors.
- **Database access from the host** — the Compose database is published on `127.0.0.1:5432` only (loopback), so IDE-run servers and tools on the same machine can connect; it is not exposed to the network.
- **Server keeps restarting** — inspect `docker compose logs server`; the usual cause is wrong database credentials in `.env` or an unreachable `CHAT_DB_URL`.
- **Reset the database** — `docker compose down -v && docker compose up -d --build`.
- **Clients fail with "Unable to open DISPLAY"** — no desktop session is available; run them from a graphical login.
- **Password reset email fails** — set `CHAT_EMAIL_USERNAME` and a Gmail app password in `.env`; the feature is disabled when they are empty.
- **Inspect the database** — `docker compose exec db psql -U chat -d chat_system`.

---

## **License**

This project is licensed under the MIT License.
