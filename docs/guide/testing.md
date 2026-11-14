# Testing guidelines

## Test stack

| Layer | Framework | Notes |
|---|---|---|
| Backend unit + integration | ScalaTest `AnyWordSpec` + testcontainers-postgresql | Real DB in Docker per test |
| Controller tests | scalatestplus-play `PlaySpec` | Full Play app via Guice |
| Frontend form logic | ScalaTest `AnyWordSpec` | Pure Scala.js, no DOM |
| Frontend component render | ScalaTest `AnyWordSpec` + jsdom | `Test / requireJsDomEnv := true` |
| External API mocks | Mockito (`MockitoSugar`) | Injected at Guice level |

## Rules

**RULE-TE-001 — Naming convention**

```scala
"ClassName or method" should {
  "describe the expected behaviour" in { ... }
  "return an error when X is missing" in { ... }
}
```

Test files mirror source paths: `server/src/main/scala/net/wiringbits/repositories/UsersRepository.scala` → `server/src/test/scala/net/wiringbits/repositories/UsersRepositorySpec.scala`.

The `should` subject must **never** include the class or object name. The spec file name already provides that context. Use only the method name, behaviour, or scenario being described.

```scala
// CORRECT — method name only; class is implied by TelegramMessageParserSpec
"parse" should {
  "reject a non-numeric amount" in { ... }
}

// WRONG — class name repeated; it is already in the file name
"TelegramMessageParser.parse" should { ... }
```

This applies to every level of nesting: `should` subjects, `when` clauses, and `in` descriptions must all describe behaviour, not re-state the type hierarchy.

@file: server/src/test/scala/net/wiringbits/repositories/UsersRepositorySpec.scala

**RULE-TE-002 — Backend integration tests: `PostgresSpec` + `RepositoryUtils`**

All tests that hit the database extend `PostgresSpec` (shares one PostgreSQL 13 testcontainer, evolved once, across the whole test JVM; tests must use random unique values and user-scoped assertions). Test data is created via `RepositoryUtils`:

```scala
class UsersRepositorySpec extends PostgresSpec {
  "create" should {
    "work" in withRepositories() { implicit repositories =>
      createUser().futureValue
    }
  }
}
```

Never seed test data with raw SQL; always go through `RepositoryUtils` factory methods so schema changes are caught at compile time.

@file: server/src/test/scala/net/wiringbits/core/PostgresSpec.scala  
@file: server/src/test/scala/utils/RepositoryUtils.scala

**RULE-TE-003 — Controller tests: Guice overrides for external deps**

Mocks are avoided everywhere possible. The one exception is controller tests: each spec file spins up a single Play application shared across all tests in that file. Because the app is shared, Mockito mocks are the only practical way to change behavior (e.g. what token is generated, whether the clock is frozen) between individual test cases.

Controller tests use a real database and mock only the external boundaries:

```scala
override def guiceApplicationBuilder(): GuiceApplicationBuilder =
  super.guiceApplicationBuilder().overrides(
    inject.bind[EmailApi].to(emailApi),
    inject.bind[ReCaptchaApi].to(captchaApi),
    inject.bind[Clock].to(clock),
    inject.bind[TokenGenerator].to(tokenGenerator)
  )
```

Use `when(tokenGenerator.next()).thenReturn(knownUUID)` to make tests deterministic.

Controller tests are also where **permission checks** are verified — confirm that an unauthenticated or wrong-user request returns the expected error before testing the happy path.

@file: server/src/test/scala/controllers/UsersControllerSpec.scala

**RULE-TE-004 — Frontend component tests: jsdom + BrowserRouter**

Component render tests use `slinky.web.ReactDOM.render` in jsdom. Wrap in `BrowserRouter` if the component calls `useHistory` or `useLocation`. Tear down with `ReactDOM.unmountComponentAtNode` in `afterEach`.

```scala
class HomePageSpec extends AnyWordSpec with BeforeAndAfterEach {
  private var container: dom.Element = uninitialized   // scala.compiletime.uninitialized

  override def beforeEach(): Unit = {
    container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
  }
  override def afterEach(): Unit = {
    ReactDOM.unmountComponentAtNode(container)
    dom.document.body.removeChild(container)
  }

  "HomePage" should {
    "render without component-reuse errors" in {
      noException should be thrownBy
        ReactDOM.render(BrowserRouter(basename = "")(HomePage(testCtx()): ReactElement), container)
    }
  }
}
```

@file: web/src/test/scala/net/wiringbits/components/pages/HomePageSpec.scala

**RULE-TE-005 — What to test**

| Layer | Test? | Notes |
|---|---|---|
| Repositories | Yes — all operations | Real DB, `PostgresSpec` |
| Actions | Yes (new features) — behaviour + error + ownership | Real DB, `PostgresSpec`; see RULE-TE-011 |
| Controllers / routes | Yes — auth/permission + request/response encoding | Full Play app; action mocked for new features (RULE-TE-011) |
| Form data / validation | Yes — all validators | Pure unit test |
| Component render | Minimal — render-without-crash | Catches Slinky element-reuse bugs |
| Page logic / UI flows | No — not currently covered | |

For new backend features, behaviour is owned by the action's integration test and the controller test is kept thin (RULE-TE-011). Older controllers exercise their behaviour end-to-end through the real action; those are grandfathered and need not be rewritten.

**RULE-TE-010 — Frontend DOM tests require justification**

DOM-based component tests (Slinky render + jsdom) are expensive to write and brittle to maintain — they break on unrelated refactors, require URLSearchParams polyfills, BrowserRouter wrappers, and careful teardown. Do not add them by default.

A DOM test is justified only when it guards against a **known runtime failure** that cannot be caught any other way — for example, an element-reuse crash caused by Slinky's `withKey` requirement, or a MUI5 component that throws at render time with certain prop combinations. These are the cases that have historically caused production incidents.

For all other frontend logic, prefer extracting a **pure function** and writing a plain unit test against it:

```scala
// Instead of rendering the component to test date formatting:
BulkExpenseCreationPage.formatDate(someDate) mustBe "2024-01-15"

// Instead of rendering the component to test a label:
BankStatementReviewLogic.resultLabel(id, merchants, Right(())) mustBe "✓ Acme"
```

If you cannot extract a pure function and a DOM test feels necessary, state the specific runtime failure it prevents in a comment on the test.

**RULE-TE-011 — New backend features: action/controller test split**

New backend features split their server tests along the layer boundary instead of driving everything through the controller:

- **The action owns behaviour.** Each action has an integration test (`PostgresSpec`, real Postgres, data via `RepositoryUtils`) covering the happy path, domain errors, conflicts, and ownership (cross-user requests must not see or mutate another user's rows). This is where behaviour is asserted.
- **The controller is thin.** The controller test covers auth/permission (unauthenticated and wrong-user requests return the expected error) and request/response encoding (the decoded request reaches the action, the action's result is encoded back). It injects a Mockito mock of the action via a Guice override and verifies the controller delegates to it with the decoded request; it does not re-assert the behaviour the action test already owns and does not need a real DB for the delegation path.

```scala
// Controller test: mock the action, assert delegation + encoding, not behaviour.
private val action = mock[CreateSubscriptionAction]
override def guiceApplicationBuilder(): GuiceApplicationBuilder =
  super.guiceApplicationBuilder().overrides(inject.bind[CreateSubscriptionAction].to(action))

when(action.apply(eqTo(userId), any)).thenReturn(Future.successful(CreateSubscription.Response(id)))
// ... POST the request, assert 200 + body, then verify(action).apply(eqTo(userId), eqTo(decodedRequest))
```

This keeps behaviour tests fast and close to the logic, and keeps controller tests focused on the web boundary. It applies to new features; existing controllers that exercise behaviour through the real action are grandfathered (RULE-TE-005).

**RULE-TE-006 — `.futureValue` for async**

Use ScalaFutures `.futureValue` to block on `Future` in tests. Use `.expectError` (defined in `PlayPostgresSpec`) to assert a `Future` fails:

```scala
val result = repository.findByEmail(unknown).futureValue
result must be(empty)

// Assert failure
intercept[RuntimeException](repository.findOrFail(unknownId).futureValue)
```

@file: server/src/test/scala/controllers/common/PlayPostgresSpec.scala (`expectError`)

**RULE-TE-007 — Always run with `CI=true` locally**

The `CI=true` flag enables `-Werror` in the Scala compiler — any warning becomes a compile error. Always test locally with the same flag CI uses:

```bash
CI=true sbt compile
CI=true sbt test
```

Running without `CI=true` can hide warnings that will fail the CI build.

**RULE-TE-008 — Format before every push: `scalafmtSbt` and `scalafmtAll`**

Two separate commands are required:

```bash
sbt scalafmtSbt   # formats build.sbt and project/*.sbt files
sbt scalafmtAll   # formats all src/**/*.scala files
git add -u        # stage any files that were reformatted
```

`scalafmtSbt` formats the build files; `scalafmtAll` formats application sources. CI runs `scalafmtCheckAll` and fails on any diff.

**RULE-TE-009 — Run tests locally, conserve CI minutes**

CI minutes are limited. Always run the relevant tests locally before pushing:

```bash
# Backend changes
CI=true sbt "server/test"

# Frontend-only changes — compile is sufficient
CI=true sbt "web/compile"

# Full suite (Docker must be running)
CI=true NODE_OPTIONS=--openssl-legacy-provider sbt test
```

Never push code you expect to be failing. Use `CI=true` locally to surface all warnings before they become CI failures.

## Exceptions — rules that change in test context

These patterns are **only acceptable in test code**. Never use them in `src/main/`.

| Exception | Where allowed | Why |
|---|---|---|
| `null.asInstanceOf[API]` | Component render tests | Component under test never calls `ctx.api` during render. Document with a comment. |
| `= uninitialized` (import `scala.compiletime.uninitialized`) | `BeforeAndAfterEach` setup vars | Replaces deprecated `= _`; CI enforces `-Werror` so `= _` fails in CI. |
| `.futureValue` (blocking) | Any test | ScalaFutures; would deadlock in production. |
| `Scheduler.trampoline()` | Monix `Var` setup in tests | Forces synchronous evaluation; unsafe outside tests. |
| `pending` | Known-broken test cases | Marks incomplete behaviour, not a skip. Remove when the feature is implemented. |

## Architectural boundaries revealed

- Repository tests import nothing from `controllers/` — repositories are properly isolated.
- Controller tests import `repositories/` directly for assertions — controllers are tightly coupled to repos (no service abstraction layer in between).
- Frontend form tests import nothing from `server/` — the frontend/backend boundary is clean.
- Component tests use `null.asInstanceOf[API]`, confirming that components should only depend on observable state from `AppContext`, not call the API directly in their render path.
