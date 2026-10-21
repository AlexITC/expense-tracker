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
| Controllers / routes | Yes — happy path + error + permission checks | Full Play app |
| Form data / validation | Yes — all validators | Pure unit test |
| Actions | No — intentionally untested directly | Thin wrappers; covered indirectly by controller tests |
| Component render | Minimal — render-without-crash | Catches Slinky element-reuse bugs |
| Page logic / UI flows | No — not currently covered | |

Actions are not tested directly because they are thin orchestration wrappers over validators and repositories, and controller tests already exercise them end-to-end.

**RULE-TE-010 — Frontend DOM tests require justification**

DOM-based component tests (Slinky render + jsdom) are expensive to write and brittle to maintain — they break on unrelated refactors, require URLSearchParams polyfills, BrowserRouter wrappers, and careful teardown. Do not add them by default.

A DOM test is justified only when it guards against a **known runtime failure** that cannot be caught any other way — for example, an element-reuse crash caused by Slinky's `withKey` requirement, or a MUI5 component that throws at render time with certain prop combinations. These are the cases that have historically caused production incidents.

For all other frontend logic, prefer extracting a **pure function** and writing a plain unit test against it:

```scala
// Instead of rendering the component to test date formatting:
BulkExpenseCreationPage.formatDate(someDate) mustBe "2024-01-15"

// Instead of rendering the component to test a label:
BankStatementReviewPage.resultLabel(id, merchants, Right(())) mustBe "✓ Acme"
```

If you cannot extract a pure function and a DOM test feels necessary, state the specific runtime failure it prevents in a comment on the test.

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
