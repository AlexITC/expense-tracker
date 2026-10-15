# Backend guidelines

Stack: Scala 3 · Play Framework · Tapir · Anorm · PostgreSQL · Pekko

## Architecture

```
controllers/      Tapir serverLogic wiring; thin — no business logic
actions/          One action class per operation; auth + validation + orchestration
repositories/     Anorm SQL; one file per table; delegates to DAOs
daos/             Raw SQL + row parsers; one DAO per table
validations/      Single-purpose validators (throw RuntimeException on failure)
config/           Typed wrappers around Play Configuration
lib/api/shared/   Tapir endpoint definitions + request/response models (shared with frontend)
```

**Strict call direction:** controllers → actions → validators/repositories/services. Never the other way. Actions do not call other actions. Repositories do not call other repositories.

When an action needs logic that another action already contains, do **not** inject and call the other action. Instead, choose one of:

1. **Inline the repository calls** — actions are thin enough that duplicating two or three DAO calls is preferable to coupling actions together.
2. **Extract to a shared repository method** — if the shared logic is fundamentally a data operation, give it a named method on the relevant repository and call it from both actions independently.
3. **Extract to a shared validator or pure function** — if the shared logic is validation or a pure computation, put it in `validations/` or a standalone object that both actions import.

```scala
// WRONG — action calling another action
class CreateExpenseFromRowAction @Inject() (createExpenseAction: CreateExpenseAction, ...) {
  def apply(...) = for {
    result <- createExpenseAction(userId, request)  // DON'T inject actions into actions
    ...
  } yield result
}

// CORRECT — both actions call the repository directly
class CreateExpenseFromRowAction @Inject() (expensesRepository: ExpensesRepository, ...) {
  def apply(...) = for {
    _ <- expensesRepository.create(...)             // same repository call the other action uses
    ...
  } yield ()
}
```

Each layer's responsibility:

- **Controller**: wires a Tapir endpoint to an action; contains only `handleRequest { for { _ <- action(...) } yield Right(response) }` and a `logger.info` call.
- **Action**: orchestrates one use case — calls validators, repositories, and external services in order. Generates application-side IDs (`UUID.randomUUID()`). Does not contain SQL.
- **Validator**: single boolean check; throws `RuntimeException` on failure. Returns `Future[Unit]` so it composes cleanly in `for` comprehensions.
- **Repository**: maps a business operation to one database transaction (or connection). Delegates SQL to its DAO. Decides atomicity boundaries. Wraps the whole body in `Future { ... }` — never composes futures inside.
- **DAO**: raw Anorm SQL + `RowParser`. No `Future`, no business logic, no cross-table calls.

## Rules

**RULE-BE-001 — Endpoint definitions live in `lib/api/shared/`**

Tapir `Endpoint[...]` objects are defined in `lib/api/shared/src/main/scala/net/wiringbits/api/endpoints/` so the frontend `ApiClient` and the backend server share the same type. Never duplicate or redefine endpoint signatures in `server/`.

```scala
// lib/api/shared — defines the contract
val createUser: Endpoint[Unit, CreateUser.Request, ErrorResponse, CreateUser.Response, Any] = ...

// server — wires it to logic
createUser.serverLogic { request => handleRequest { ... } }
```

@file: lib/api/shared/src/main/scala/net/wiringbits/api/endpoints/UsersEndpoints.scala  
@file: server/src/main/scala/controllers/UsersController.scala

**RULE-BE-002 — Auth via `.in(userAuth)`**

Protect endpoints by adding `.in(userAuth)` in the endpoint definition, not by checking the session manually in controller logic. The `authHandler` in `controllers/package.scala` extracts the session UUID and passes it as a `Future[UUID]` to `serverLogic`.

```scala
// lib/api/shared — mark as protected
val getMe: Endpoint[Future[UUID], Unit, ErrorResponse, GetCurrentUser.Response, Any] =
  base.get.in("auth" / "me").in(userAuth).out(...)

// server — userId already validated
getMe.serverLogic { userId => handleRequest { actions.getUser(userId) } }
```

@file: lib/api/shared/src/main/scala/net/wiringbits/api/endpoints/AuthEndpoints.scala  
@file: server/src/main/scala/controllers/package.scala (authHandler)

**RULE-BE-003 — All handlers wrapped in `handleRequest`**

Every `serverLogic` body must use the `handleRequest { ... }` wrapper from `controllers/package.scala`. It converts any thrown `RuntimeException` into an `ErrorResponse` and logs it. Uncaught exceptions outside this wrapper will not be handled consistently.

```scala
endpoint.serverLogic { _ => handleRequest {
  for {
    _ <- validations.validateSomething(...)
    result <- actions.doSomething(...)
  } yield result
}}
```

@file: server/src/main/scala/controllers/package.scala (handleRequest, lines 33–45)

**RULE-BE-004 — Config through typed wrappers**

Every feature's config is defined in three places, always all three together:

**1 — `server/src/main/scala/net/wiringbits/config/MyFeatureConfig.scala`**

```scala
case class MyFeatureConfig(apiKey: String, enabled: Boolean) {
  // Override toString to mask secrets so they are never printed in logs
  override def toString: String = s"MyFeatureConfig(enabled = $enabled)"
}
object MyFeatureConfig {
  def apply(config: Configuration): MyFeatureConfig =
    MyFeatureConfig(
      apiKey  = config.get[String]("apiKey"),
      enabled = config.get[Boolean]("enabled")
    )
}
```

**2 — `ConfigModule` — register a `@Provides @Singleton` provider:**

```scala
@Provides @Singleton
def myFeatureConfig(global: Configuration): MyFeatureConfig = {
  val config = MyFeatureConfig(global.get[Configuration]("myFeature"))
  logger.info(s"Config loaded: $config")
  config
}
```

**3 — `application.conf` — one named block with safe defaults and `${?ENV_VAR}` overrides:**

```hocon
myFeature {
  # Brief comment describing what this key controls
  enabled = false
  enabled = ${?MY_FEATURE_ENABLED}

  apiKey = "REPLACE_ME"
  apiKey = ${?MY_FEATURE_API_KEY}
}
```

Rules:
- Never call `config.get[...]` inside a controller or action — inject the typed config class instead.
- Always provide a safe default (e.g. `false`, `"none"`, `"REPLACE_ME"`) so the app starts without every env var set.
- Secrets must not appear in `toString` — override it to emit only non-sensitive fields.

@file: server/src/main/scala/net/wiringbits/config/TelegramConfig.scala (reference implementation)  
@file: server/src/main/resources/application.conf (all config keys with `${?ENV_VAR}` overrides)

**RULE-BE-005 — Repositories wrap operations in `Future { }`, never compose**

A repository method body is always `Future { database.with[Connection/Transaction] { ... } }`. The whole operation is a single synchronous block inside one `Future`. Never compose multiple `Future`s inside a repository — move that orchestration to the action layer.

```scala
// CORRECT — one Future, one transaction block
def create(request: User.CreateUser): Future[Unit] = Future {
  database.withTransaction { implicit conn =>
    UsersDAO.create(request)
    UserTokensDAO.create(tokenRequest)
    UserLogsDAO.create(logRequest)
  }
}

// WRONG — composing futures inside a repository
def create(request: User.CreateUser): Future[Unit] = {
  usersDAO.insert(request).flatMap { _ =>       // DON'T do this
    userLogsDAO.insert(logRequest)
  }
}
```

Use `withTransaction` when multiple writes must be atomic. Use `withConnection` for reads.

@file: server/src/main/scala/net/wiringbits/repositories/UsersRepository.scala

**RULE-BE-006 — Repositories own atomicity decisions**

If several writes must succeed or fail together, the repository is the right place to put them inside a single `withTransaction`. Even when there is business logic involved (e.g. "create user + create token + log event"), prioritize atomicity by keeping all three writes in one transaction rather than splitting them across separate repository calls in the action.

```scala
// All three writes are in one transaction — atomic
def create(request: User.CreateUser): Future[Unit] = Future {
  database.withTransaction { implicit conn =>
    UsersDAO.create(request)
    UserTokensDAO.create(tokenRequest)
    UserLogsDAO.create(logRequest)
  }
}
```

**RULE-BE-007 — Logging: what to log and what to avoid**

Controllers log the entry point of each request at `INFO` level. Repositories and DAOs do not log.

```scala
// Controller — log the operation with a non-sensitive identifier
logger.info(s"Create user: ${request.email.string}")
logger.info(s"Update password for: $userId")
```

**Never log confidential data**: passwords (plain or hashed), tokens, session IDs, credit card numbers, or any PII beyond what is strictly needed for debugging. Log the user ID or email when necessary; never log the password field.

```scala
// WRONG — logging a password
logger.info(s"Reset password for $userId, new password = ${request.password}")

// CORRECT — log only the non-sensitive identifier
logger.info(s"Reset password for: $userId")
```

Use `logger.warn` for recoverable job failures and `logger.error` for unexpected failures that need investigation.

@file: server/src/main/scala/controllers/UsersController.scala (logging examples)  
@file: server/src/main/scala/net/wiringbits/tasks/BackgroundJobsExecutorTask.scala (warn/error examples)

**RULE-BE-008 — Use Chimney for case class mappings**

When converting between two case classes that share most fields (e.g., a request model to a domain model, or a DAO row to an API response), use [Chimney](https://scalalandio.github.io/chimney/) rather than manual field-by-field construction:

```scala
import io.scalaland.chimney.dsl.*

// CORRECT — Chimney derives the mapping; only specify the fields that differ
val domainObj = requestModel.into[DomainModel]
  .withFieldConst(_.id, UUID.randomUUID())
  .withFieldConst(_.createdAt, clock.instant())
  .transform

// WRONG — manual mapping duplicates field names and silently misses new fields
val domainObj = DomainModel(
  id = UUID.randomUUID(),
  name = requestModel.name,
  amount = requestModel.amount,
  // ... and so on
)
```

Chimney makes new fields (added to either side) fail at compile time rather than silently carrying a wrong default.

**RULE-BE-009 — Actions delegate conditionals to validators**

Business-rule conditionals belong in `validations/`, not inline in action bodies. Actions must read as a flat sequence of `await` calls:

```scala
// CORRECT — condition lives in a validator
class CreateExpenseFromRowAction @Inject() (
  validatePositiveAmount: ValidatePositiveAmount, ...
) {
  def apply(...) = for {
    _ <- validatePositiveAmount(row.amount)
    _ <- expensesRepository.create(...)
  } yield ()
}

// WRONG — inline conditional in the action body
def apply(...) = for {
  _ <- if (row.amount >= 0) Future.unit
       else Future.failed(new RuntimeException("Amount must be non-negative"))
  _ <- expensesRepository.create(...)
} yield ()
```

Single-purpose validators in `validations/` are independently testable and reusable. Inline `if`-guards in actions are neither.

## Background jobs

The app uses a durable background job queue stored in the `background_jobs` table. Jobs are rows with a `type` (TEXT enum), a `payload` (JSONB), a `status` (pending / success / failed), and an `execute_at` timestamp.

**When to use background jobs**: for work that must happen asynchronously and survive a server restart — primarily email delivery. Do not use background jobs for synchronous validation or simple async work that can be awaited.

**Adding a new job type:**

1. Add a new case object to `BackgroundJobType` (enumeratum `Enum`).
2. Add a new case class to `BackgroundJobPayload` with a `Format` for JSON serialisation.
3. Enqueue from the repository inside a `withTransaction` block using `BackgroundJobDAO.create`.
4. Add a `case` branch in `BackgroundJobsExecutorTask.execute` that handles the new type.

```scala
// 1. Enqueue inside a repository transaction — guaranteed to exist or not exist with the parent write
private def sendEmailLater(userId: UUID, emailMessage: EmailMessage)(implicit conn: Connection): Unit = {
  val payload = BackgroundJobPayload.SendEmail(email, subject, body)
  BackgroundJobDAO.create(
    BackgroundJobData.Create(
      id = UUID.randomUUID(),
      `type` = BackgroundJobType.SendEmail,
      payload = payload,
      status = BackgroundJobStatus.Pending,
      executeAt = clock.instant(),
      ...
    )
  )
}

// 2. Execute in the task
case BackgroundJobType.SendEmail =>
  job.payload.asOpt[BackgroundJobPayload.SendEmail] match {
    case Some(p) => sendEmail(p)
    case None    => Future.failed(new RuntimeException(s"Invalid payload for job ${job.id}"))
  }
```

**Caution:** Adding fields to an existing `BackgroundJobPayload` case class is a breaking change — jobs already in the database will fail JSON parsing. Prefer adding a new job type if the shape changes.

The executor polls on a configurable interval (`BackgroundJobsExecutorConfig`), throttles to 100 jobs per minute, and processes one at a time (`Sink.foreachAsync(1)`) to avoid overloading the app. Failed jobs are retried with exponential back-off (`DelayGenerator.createDelay`).

@file: server/src/main/scala/net/wiringbits/tasks/BackgroundJobsExecutorTask.scala  
@file: server/src/main/scala/net/wiringbits/models/jobs/BackgroundJobPayload.scala  
@file: server/src/main/scala/net/wiringbits/models/jobs/BackgroundJobType.scala  
@file: server/src/main/scala/net/wiringbits/repositories/UsersRepository.scala (sendEmailLater)

## Error handling

Validators throw `RuntimeException` with a human-readable message. `handleRequest` catches all throwables and turns them into `Left(ErrorResponse(message))`. There is no typed exception hierarchy — all failures surface as HTTP 500 with an error message string. This is a known limitation (see [antipatterns.md](antipatterns.md)).

@file: server/src/main/scala/net/wiringbits/validations/ValidatePasswordMatches.scala

## Anti-patterns

See [antipatterns.md](antipatterns.md) §Backend.
