# Anti-patterns

Concrete things found in this codebase that future code should not repeat.

---

## Frontend

**AP-FE-01 — Object-level `val` holding a React element**

Slinky marks a `KeyAddingStage` as consumed when `.build` is called (on first render). Storing one in an object-level `val` means the second render throws `IllegalStateException: already been built`.

```scala
// BEFORE (breaks on re-render)
object MyWidget {
  private val icon = mui.Icon(muiIcons.Home())   // built once, dead on re-render
  val component = FunctionalComponent[Props] { _ => icon }
}

// AFTER — keep icons/elements inside the render function
val component = FunctionalComponent[Props] { _ =>
  mui.Icon(muiIcons.Home())
}
```

@file: web/src/main/scala/net/wiringbits/components/pages/HomePage.scala (`aiPoints` was object-level, moved inside render)

**AP-FE-02 — Same `val` element used twice in one render**

Even inside the render function, a single `val` holding a `KeyAddingStage` can only appear once in the returned tree. Use `def` for elements that appear in multiple places.

```scala
// BEFORE (second use throws)
val arrow = mui.Icon(muiIcons.Sync())
Fragment(boxA, arrow, boxB, arrow)   // second `arrow` already consumed

// AFTER
def arrow = mui.Icon(muiIcons.Sync())
Fragment(boxA, arrow, boxB, arrow)   // each call returns a fresh instance
```

@file: web/src/main/scala/net/wiringbits/components/pages/HomePage.scala (`def syncArrow`)

**AP-FE-03 — Hardcoded hex colors instead of theme tokens**

Hard-coded colors break dark mode, ignore the brand token, and scatter the colour palette across files.

```scala
// BEFORE
js.Dynamic.literal(color = "#0E8C6F", background = "#FFFFFF")

// AFTER
js.Dynamic.literal(color = "primary.main", background = "background.paper")
```

@file: web/src/main/scala/net/wiringbits/components/widgets/AppBar.scala — note remaining hardcoded values; AppBar intentionally stays primary-coloured so some hex is necessary, but prefer tokens everywhere else.

**AP-FE-04 — Fixed `gridTemplateColumns` without responsive breakpoints**

A hard-coded `"1fr 1fr 1fr"` will overflow on mobile. Use MUI's breakpoint object:

```scala
// BEFORE
js.Dynamic.literal(display = "grid", gridTemplateColumns = "1fr 1fr 1fr")

// AFTER
js.Dynamic.literal(
  display = "grid",
  gridTemplateColumns = js.Dynamic.literal(xs = "1fr", sm = "1fr 1fr", md = "1fr 1fr 1fr")
)
```

**AP-FE-05 — Wrapping SVG icons in `mui.Icon`**

`mui.Icon` is a font-icon wrapper — it renders a `<span class="MuiIcon-root">` with `overflow: hidden` sized to `1em`. Nesting a `muiIcons.*` SVG icon component inside it clips the SVG.

```scala
// BEFORE (clips the icon)
mui.Icon(muiIcons.CheckCircle()).sx(js.Dynamic.literal(fontSize = 20).asInstanceOf[CSSPropertiesUtils])

// AFTER — use the icon component directly
muiIcons.CheckCircle().sx(js.Dynamic.literal(fontSize = 20).asInstanceOf[CSSPropertiesUtils])
```

The same applies to button props: use the icon component directly, not wrapped.

```scala
// BEFORE
button.endIcon(mui.Icon(muiIcons.ArrowForward()))

// AFTER
button.endIcon(muiIcons.ArrowForward())
```

**AP-FE-06 — Duplicated form submission boilerplate**

All forms repeat the same ~25-line submit handler: check `isSubmitButtonEnabled` → `setFormData(_.submit)` → API call → `onComplete`. This is not yet abstracted. When adding a new form, copy the pattern exactly; do not invent a different structure. Track the abstraction as future work.

@file: web/src/main/scala/net/wiringbits/components/widgets/SignUpForm.scala  
@file: web/src/main/scala/net/wiringbits/components/widgets/SignInForm.scala  
@file: web/src/main/scala/net/wiringbits/components/widgets/EditUserForm.scala

**AP-FE-07 — `js.Dynamic` / `asInstanceOf` in component bodies, pages, or widgets**

`js.Dynamic` and `asInstanceOf` are **banned** from component bodies, page objects, and widgets. They belong exclusively in the `compat/` layer. Use the approved compat objects — see RULE-FE-014 in [frontend.md](frontend.md) for the full helper table.

**AP-FE-08 — Re-implementing MUI components**

Hand-rolling pagination controls, tables, or dialogs when MUI 5 already provides them (`mui.Pagination`, `mui.Table`, `mui.Dialog`, etc.) adds code that diverges from MUI behaviour and requires maintenance. Always prefer wrapping the existing MUI component. See RULE-FE-015 in [frontend.md](frontend.md).

---

## Backend

**AP-BE-01 — No typed exception hierarchy**

All failures throw bare `RuntimeException`. `handleRequest` catches everything and returns HTTP 500 with the message string. There is no way to return 400 (bad input) vs 404 (not found) vs 401 (auth failure) distinctly.

```scala
// Current (all become 500)
if (user.isEmpty) throw new RuntimeException("User not found")

// Better (not yet implemented in this codebase)
if (user.isEmpty) throw NotFoundException("User not found")
// and map NotFoundException → 404 in the error handler
```

@file: server/src/main/scala/net/wiringbits/validations/ValidatePasswordMatches.scala  
@file: server/src/main/scala/net/wiringbits/validations/ValidateUserToken.scala

**AP-BE-02 — Raw `getString` in ConfigLoader instead of typed wrappers**

Some model-level config loaders call `config.getString(path)` directly, bypassing the typed wrapper pattern used by `SwaggerConfig` and `AWSConfig`. This skips compile-time safety.

```scala
// BEFORE
implicit val loader: ConfigLoader[ReCaptchaSiteKey] =
  (config, path) => ReCaptchaSiteKey(config.getString(path))

// AFTER — consistent with SwaggerConfig pattern
object MyConfig {
  def apply(config: Configuration): MyConfig =
    MyConfig(value = config.get[String]("my.key"))
}
```

@file: server/src/main/scala/net/wiringbits/models/ReCaptchaSiteKey.scala  
@file: server/src/main/scala/net/wiringbits/config/SwaggerConfig.scala (correct pattern)

**AP-BE-03 — Inline conditionals in action bodies**

Business-rule `if`-guards written directly inside action `for` comprehensions are untestable in isolation and invisible to reviewers who expect actions to be flat orchestration sequences. Extract every conditional into a validator in `validations/`. See RULE-BE-009 in [backend.md](backend.md).

**AP-BE-04 — Manual field-by-field case class mapping**

Manually copying fields between two case classes misses fields added later (silent compile-time gap) and duplicates names that Chimney can derive. Use Chimney for any case-class-to-case-class conversion where two or more fields are shared. See RULE-BE-008 in [backend.md](backend.md).

---

## CI / Workflow

**AP-CI-01 — Adding external GitHub Actions without permission**

Do not add new external GitHub Actions (e.g., `uses: third-party/some-action@v1`) without explicit approval. External actions run arbitrary code in the CI environment and introduce supply-chain risk. If the functionality can be reimplemented as a shell step, do so. If an external action is truly necessary, raise it for review before adding it.

---

## Data

**AP-DB-01 — Missing `WHERE user_id` on user-owned queries**

Any query on a user-owned table that does not filter by the authenticated user's ID is an authorization bypass. The currently authenticated user's UUID must appear in every WHERE clause for user-owned data.

@file: server/src/main/scala/net/wiringbits/repositories/daos/UsersDAO.scala (positive examples of scoped queries)

---

## Testing

**AP-TE-01 — `= _` for uninitialized vars in test setup**

`var x: T = _` is deprecated in Scala 3.4+ and causes a warning that becomes a compile error under `-Werror`. Always use `= uninitialized` (with `import scala.compiletime.uninitialized`).

```scala
// BEFORE (CI fails)
private var container: dom.Element = _

// AFTER
import scala.compiletime.uninitialized
private var container: dom.Element = uninitialized
```

@file: web/src/test/scala/net/wiringbits/components/pages/HomePageSpec.scala (fixed)

**AP-TE-02 — `pending` tests left indefinitely**

`pending` is acceptable for known-incomplete behaviour but must not accumulate. Every `pending` block should have a linked issue or TODO comment explaining what needs to change before it can be un-pendingd.

@file: server/src/test/scala/net/wiringbits/repositories/BackgroundJobsRepositorySpec.scala (lines 96, 117)

**AP-TE-03 — Repeating the class name in companion object test subjects**

When the spec file already names the class under test, repeating it in the `"subject"` string is noise:

```scala
// WRONG
"ImportRuleMatcher.normalize" should { "do X" in { ... } }

// CORRECT — method name only; the spec class provides the class context
"normalize" should { "do X" in { ... } }
```

See RULE-TE-001 in [testing.md](testing.md).

---

## Hard rules — no exceptions

**Never push a PR that does not compile.** A PR with compilation errors is not reviewable and blocks everyone who depends on that branch. Run `CI=true sbt Test/compile` locally before every push. If CI is the first thing that catches a compile error, the PR must not be merged — fix it first.

**Never implement an issue that doesn't make sense.** If an issue is vague, contradictory, or asks for something that doesn't exist in the codebase, add the `needs-info` label and post a comment explaining what is unclear. Implementing a misunderstood issue produces throwaway work. Pushback is cheaper than a wrong PR.
