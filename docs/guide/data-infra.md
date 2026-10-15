# Data & infrastructure guidelines

## Rules

**RULE-DB-001 — Anorm raw SQL with explicit row parsers**

All DB access uses Anorm. No ORM. Queries are raw SQL strings; results are mapped via `Macro.parser[CaseClass]()` or hand-written `RowParser`s. Custom column parsers in `daos/package.scala` handle CITEXT, enums, and JSON.

```scala
// Read
SQL"""SELECT id, name, email FROM users WHERE id = $userId"""
  .as(Macro.parser[User]("id", "name", "email").single)

// Write
SQL"""INSERT INTO users (id, name, email) VALUES ($id, $name, $email)""".execute()
```

Use `database.withConnection { implicit conn => ... }` for reads.  
Use `database.withTransaction { implicit conn => ... }` for **all** writes — even single-statement ones. This ensures that adding a second write later (an audit log row, a cache invalidation) never requires refactoring the transaction wrapper, and the database already optimises single-statement transactions at no extra cost.

@file: server/src/main/scala/net/wiringbits/repositories/daos/UsersDAO.scala  
@file: server/src/main/scala/net/wiringbits/repositories/daos/package.scala

**RULE-DB-002 — User-scoped queries on every user-owned table**

Every query on a user-owned table must include `WHERE user_id = $userId`. Never trust a client-supplied ID without verifying ownership.

**Exception — background job pipeline methods:** DAOs called exclusively from background job tasks (never from a controller or action) may omit the `user_id` filter when they operate by an internal ID (document ID, job ID) that the pipeline itself generated and controls. These methods must be `private` to their DAO or clearly named `*Internal` / `*BySystem` to signal they are not safe for direct controller use. Any method reachable from a controller must still be user-scoped.

```scala
// OK — only the background pipeline calls this; it processes a document it owns
private def findByDocumentId(documentId: UUID)(implicit conn: Connection): Option[Receipt] = ...

// NOT OK — reachable from a controller; must include user_id
def find(id: UUID, userId: UUID)(implicit conn: Connection): Option[Receipt] = ...
```

**RULE-DB-003 — DatabaseExecutionContext**

All repository calls run on `DatabaseExecutionContext` (fixed-size pool sized to match HikariCP's 9 connections). Never run DB operations on the default `ExecutionContext`.

@file: server/src/main/scala/net/wiringbits/repositories/UsersRepository.scala

---

## Migration conventions (Play Evolutions)

Migrations live in `server/src/main/resources/evolutions/default/` numbered sequentially (`1.sql`, `2.sql`, …). Auto-apply is on in all environments. Every file has only a `-- !Ups` section — no `-- !Downs` blocks, ever.

**RULE-DB-004 — Constraint naming**

| Type | Pattern | Example |
|---|---|---|
| Primary key | `{table}_{col}_pk` | `CONSTRAINT users_user_id_pk PRIMARY KEY (user_id)` |
| Unique | `{table}_{col}_unique` | `CONSTRAINT users_email_unique UNIQUE (email)` |
| Foreign key | `{table}_{reftable}_fk` | `CONSTRAINT user_logs_users_fk FOREIGN KEY (user_id) REFERENCES users(user_id)` |
| Index | `{table}_{col}_index` | `CREATE INDEX user_logs_user_id_index ON user_logs USING BTREE (user_id)` |
| Unique index (FK column that must also be unique) | `{table}_{col}_unique` | `CREATE UNIQUE INDEX mcp_api_token_user_id_unique ON mcp_api_token USING BTREE (user_id)` |

Always name constraints explicitly. Anonymous constraints cannot be referenced in error messages or future migrations.

@file: server/src/main/resources/evolutions/default/1.sql

**RULE-DB-005 — Foreign key indexes**

Every foreign key column must have a corresponding `CREATE INDEX … USING BTREE` statement immediately after the `CREATE TABLE` block. This prevents full table scans when the referenced row is deleted.

```sql
CREATE TABLE user_logs (
    user_log_id UUID NOT NULL,
    user_id     UUID NOT NULL,
    CONSTRAINT user_logs_pk        PRIMARY KEY (user_log_id),
    CONSTRAINT user_logs_users_fk  FOREIGN KEY (user_id) REFERENCES users(user_id)
);
CREATE INDEX user_logs_user_id_index ON user_logs USING BTREE (user_id);
```

If the FK column must also be unique, do not add both a `UNIQUE` table constraint and a separate `CREATE INDEX` — a `UNIQUE` constraint already creates its own supporting index, so the two together are a redundant second index on the same column (wasted write overhead, no read benefit). Instead, define a single `CREATE UNIQUE INDEX {table}_{col}_unique … USING BTREE (col)` in place of both: it enforces uniqueness and satisfies this rule's index requirement in one index.

```sql
-- CORRECT — one index, does both jobs
CREATE TABLE mcp_api_token (
    id      UUID NOT NULL,
    user_id UUID NOT NULL,
    CONSTRAINT mcp_api_token_id_pk    PRIMARY KEY (id),
    CONSTRAINT mcp_api_token_users_fk FOREIGN KEY (user_id) REFERENCES users(user_id)
);
CREATE UNIQUE INDEX mcp_api_token_user_id_unique ON mcp_api_token USING BTREE (user_id);

-- WRONG — CONSTRAINT ... UNIQUE already creates an index; this adds a second, redundant one
CREATE TABLE mcp_api_token (
    ...
    CONSTRAINT mcp_api_token_user_id_unique UNIQUE (user_id),
    CONSTRAINT mcp_api_token_users_fk FOREIGN KEY (user_id) REFERENCES users(user_id)
);
CREATE INDEX mcp_api_token_user_id_index ON mcp_api_token USING BTREE (user_id);
```

When the FK column does *not* need to be unique, keep using a plain `CREATE INDEX` per the example above.

@file: server/src/main/resources/evolutions/default/1.sql (lines 26–30)

**RULE-DB-006 — Primary keys are single-column server-generated UUIDs**

No compound primary keys. No `SERIAL` / `BIGSERIAL`. No `DEFAULT gen_random_uuid()`. The application generates the UUID before calling the repository and passes it in.

```sql
-- Column definition — no DEFAULT
user_id UUID NOT NULL,
CONSTRAINT users_user_id_pk PRIMARY KEY (user_id)
```

```scala
// Application generates the ID
val userId = UUID.randomUUID()
usersDAO.create(userId, name, email, ...)
```

**RULE-DB-007 — Timestamps are always `TIMESTAMPTZ`**

All timestamp columns use `TIMESTAMPTZ` (timestamp with time zone), never bare `TIMESTAMP`.

| Use | Definition |
|---|---|
| Creation time | `created_at TIMESTAMPTZ NOT NULL` |
| Mutation time | `updated_at TIMESTAMPTZ NOT NULL` |
| Scheduled time | `execute_at TIMESTAMPTZ NOT NULL` |
| Optional domain timestamp | `verified_on TIMESTAMPTZ NULL` |

See RULE-DB-016 for why these have no `DEFAULT` clause.

@file: server/src/main/resources/evolutions/default/1.sql (lines 12–13, 25, 57)

@file: server/src/main/resources/evolutions/default/1.sql (lines 12–13, 25, 57)

**RULE-DB-008 — Enums stored as TEXT with a comment**

Do not create SQL `ENUM` types. Store domain enumerations as `TEXT NOT NULL` and document the allowed values with a comment.

```sql
status TEXT NOT NULL, -- pending/success/failed
type   TEXT NOT NULL, -- EmailVerification/PasswordReset
```

Map to Scala sealed traits in the repository layer; the DAO's `package.scala` contains the `ColumnParser` that validates the string.

@file: server/src/main/resources/evolutions/default/1.sql (lines 35, 52)  
@file: server/src/main/scala/net/wiringbits/repositories/daos/package.scala

**RULE-DB-009 — CITEXT only for email**

`CITEXT` (case-insensitive text) is used solely for the `email` column so that uniqueness and lookup are case-insensitive. Do not apply it to other columns.

```sql
email CITEXT NOT NULL,
CONSTRAINT users_email_unique UNIQUE (email)
```

**RULE-DB-010 — No CHECK constraints**

Business-rule validation belongs in the application layer (validators / validated types). Do not add `CHECK` constraints to the schema.

**RULE-DB-011 — Structured payloads use JSONB**

When a row must carry variable structure (e.g. background job payloads), use `JSONB NOT NULL`.

```sql
payload JSONB NOT NULL,
```

@file: server/src/main/resources/evolutions/default/2.sql (line 8)

**RULE-DB-012 — DROP TABLE goes in `-- !Ups`**

When a table is superseded, drop it in the next sequential migration's `-- !Ups` block. Do not try to undo it with a `-- !Downs`.

@file: server/src/main/resources/evolutions/default/2.sql (DROP TABLE user_notifications)

**RULE-DB-013 — All list queries must have `ORDER BY`**

Every `SELECT` that can return more than one row must have an explicit `ORDER BY` clause. Without it, result order is non-deterministic: integration tests become flaky, UI lists flicker on re-fetch, and pagination produces inconsistent pages.

```sql
-- CORRECT
SELECT id, name FROM merchant
WHERE user_id = $userId
ORDER BY name

-- WRONG — result order depends on internal storage
SELECT id, name FROM merchant
WHERE user_id = $userId
```

Choose the most meaningful business ordering for the entity (usually `name` for human-readable lists, `created_at` for event streams). When ordering by a non-unique column, add a secondary sort on the primary key to guarantee a stable total order:

```sql
ORDER BY created_at DESC, id
```

**RULE-DB-014 — One evolution file per PR**

A single PR or work item must add at most one new evolution file. If you discover a mistake in a schema added on the same branch — a wrong column name, a wrong type, a missing index — fix it by editing the existing evolution file for that branch, not by adding another one. Evolution files are immutable once merged to main; only then should a new sequential file be added.

```
-- CORRECT: branch adds 7.sql with telegram tables; a fix to column name stays in 7.sql

-- WRONG: 7.sql creates the tables, 8.sql renames a column, 9.sql widens a VARCHAR
--        All three changes belong in 7.sql
```

**RULE-DB-015 — Use `TEXT`, not `VARCHAR(n)`**

All variable-length string columns must be declared as `TEXT`. Do not use `VARCHAR(n)` — PostgreSQL stores both identically, so the length cap adds no performance benefit and only creates pain when limits need changing (requiring a migration). The sole exception is `CITEXT` for email (see RULE-DB-009).

```sql
-- CORRECT
token TEXT NOT NULL,
name  TEXT NOT NULL,

-- WRONG — artificial cap, migration needed to change it
token VARCHAR(64) NOT NULL,
name  VARCHAR(255) NOT NULL,
```

Application-level length validation (in validated types or action layer) is where limits belong, not in the column definition.

**RULE-DB-016 — No `DEFAULT` clauses in the schema; the application supplies every value**

No column — of any type, including timestamps — gets a `DEFAULT`. The application always passes the value explicitly on insert/update. For timestamps this means the injected `Clock` (`clock.instant()`, never `Instant.now()` directly — this is what makes the value mockable/fixed in tests), threaded through from action to repository to DAO.

```sql
-- CORRECT
created_at TIMESTAMPTZ NOT NULL,
error_count INT NOT NULL,

-- WRONG — the database decides a value the application didn't
created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
error_count INT DEFAULT 0,
```

```scala
// Application generates and passes every value, including timestamps
class CreateXAction @Inject() (repo: XRepository)(implicit ec: ExecutionContext, clock: Clock) {
  def apply(...): Future[...] = repo.create(id, ..., createdAt = clock.instant())
}
```

No exceptions — this supersedes any prior carve-out for "meaningful invariant" defaults (e.g. a counter starting at zero): the application sets that value too. Some evolutions predating this rule still use `DEFAULT NOW()` or `DEFAULT 0` (e.g. `1.sql`) — don't copy that pattern into new tables.

---

## Infrastructure

Deployment is Ansible-based — no Docker Compose in production.

| Playbook | Purpose |
|---|---|
| `infra/server.yml` | Deploy Play backend |
| `infra/web.yml` | Deploy Scala.js frontend |
| `infra/nginx.yml` | Nginx reverse proxy + SSL |

Target OS: Ubuntu 20.04+. Config template: `infra/config/server/dev.env.j2`.

For local development, run PostgreSQL directly. Docker is only needed to run the integration test suite (testcontainers spins up PostgreSQL automatically).

@file: infra/README.md

## Anti-patterns

See [antipatterns.md](antipatterns.md) §Data.
