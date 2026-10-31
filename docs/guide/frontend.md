# Frontend guidelines

Stack: Scala.js 1.x · Slinky 0.7 · React 18 · MUI 5 (ScalablyTyped) · Monix 3

## Rules

**RULE-FE-001 — Component definition pattern**

Every component follows this exact shape:

```scala
object MyWidget {
  def apply(ctx: AppContext, user: User): KeyAddingStage =
    component(Props(ctx, user))

  case class Props(ctx: AppContext, user: User)

  val component: FunctionalComponent[Props] = FunctionalComponent[Props] { props =>
    // hooks, then render
  }
}
```

`def apply` returns `KeyAddingStage` (not `ReactElement`). Props is always a case class.

@file: web/src/main/scala/net/wiringbits/components/widgets/AppBar.scala  
@file: web/src/main/scala/net/wiringbits/components/widgets/SignUpForm.scala

**RULE-FE-002 — Object-level `val` safety**

`val` at the object/companion level is safe **only** for plain JS style objects (`js.Dynamic.literal(...)`, `new CSSPropertiesUtils { ... }`). These hold no React state and are re-usable.

Never store `KeyAddingStage` or `ReactElement` in object-level `val`s. Slinky calls `.build` once, marking the element consumed; a second use throws `IllegalStateException: already been built`.

```scala
// SAFE — plain JS object, no React element
private val cardSx = js.Dynamic.literal(borderRadius = 2, padding = "16px")

// UNSAFE — KeyAddingStage captured at object level
private val myIcon = mui.Icon(muiIcons.Home())  // breaks on second render
```

@file: web/src/main/scala/net/wiringbits/components/widgets/AppBar.scala (navLinkActiveSx, signInBtnSx — safe examples)

**RULE-FE-003 — `def` for elements used more than once**

If a React element must appear in multiple places within one render, use `def` so each call site gets a fresh `KeyAddingStage`:

```scala
// Inside FunctionalComponent body:
def syncArrow = mui.Box()(mui.Icon(muiIcons.Sync()))
// use syncArrow twice — each call creates a new instance
```

@file: web/src/main/scala/net/wiringbits/components/pages/HomePage.scala (`def syncArrow`)

**RULE-FE-004 — State**

| State kind | Mechanism | Example |
|---|---|---|
| Global (auth, dark mode, language) | `AppContext` + monix `Var` with `$` prefix | `ctx.$auth`, `ctx.$darkMode` |
| Read a global reactive value | `ReactiveHooks.useDistinctValue(ctx.$auth)` | — |
| Local form / UI state | `Hooks.useState(initialValue)` | `val (open, setOpen) = Hooks.useState(false)` |
| Derived / memoised | `SafeHooks.useMemo(key)(() => ...)` | see RULE-FE-006 |

@file: web/src/main/scala/net/wiringbits/AppContext.scala  
@file: web/src/main/scala/net/wiringbits/core/ReactiveHooks.scala

**RULE-FE-005 — No `useContext` — pass `AppContext` via Props**

React's `useContext` is not used in this codebase. `AppContext` is passed explicitly through `Props` at every level. This keeps the data-flow visible and avoids implicit coupling.

```scala
// Every component takes ctx as the first parameter
def apply(ctx: AppContext, ...): KeyAddingStage = component(Props(ctx, ...))
```

**RULE-FE-006 — hook dependency lists: use `SafeHooks`, keys are `String`**

React compares each hook dependency with `Object.is`, which only compares by **value** for JS primitives. In Scala.js
that means **`String`, `Int`, `Double`, `Boolean` only** — NOT `Long` or `Char` (both boxed objects), and no collection,
`Option`, tuple, case class, `UUID` or `BigDecimal` (all objects, compared by **reference**, so a fresh instance each
render looks "changed" and silently re-fires the hook every render). The container (`js.Array` vs `List`) is irrelevant —
Slinky takes `Iterable[Any]` and React compares the elements. All of this is proven in `HookDepsProbeSpec`.

Use `SafeHooks`, which forces every dependency to a `String` key so the trap cannot occur:

| Intent | SafeHooks | React equivalent |
|---|---|---|
| Run once (mount only) | `SafeHooks.useEffect()(fn)` | `useEffect(fn, [])` |
| Re-run when something changes | `SafeHooks.useEffect(key)(fn)` | `useEffect(fn, [key])` |
| Memoize once | `SafeHooks.useMemo()(() => expr)` | `useMemo(() => expr, [])` |
| Re-memoize when something changes | `SafeHooks.useMemo(key)(() => expr)` | `useMemo(() => expr, [key])` |

Derive the key from whatever the hook truly depends on — `id.toString`, interpolation (`s"$a:$b"`), or
`ids.map(_.toString).mkString(",")`. No keys means compute-once (an empty dependency list; empty `js.Array`, empty
`List` and `""` are all equivalent — also proven). For the rare "run on every render" effect, call `Hooks.useEffect(fn)`
with no dependency argument.

```scala
// Run-once memoization
SafeHooks.useMemo() { () =>
  AsyncComponent[String](fetch = ..., render = ...)
}

// Re-run when the statement or its polling flag changes
SafeHooks.useEffect(s"$stmtId:$polling") { () =>
  val timer = startPolling()
  () => timer.cancel()
}
```

The low-level hook utilities in `net.wiringbits.core` are the exception: `ReactiveHooks` deliberately keys on a stable
monix `Var` reference (reference identity is the intended semantic — re-subscribe only when a different `Var` is passed),
so it uses `Hooks` directly.

@file: web/src/main/scala/net/wiringbits/core/SafeHooks.scala  
@file: web/src/test/scala/net/wiringbits/components/HookDepsProbeSpec.scala  
@file: web/src/main/scala/net/wiringbits/core/ReactiveHooks.scala

**RULE-FE-007 — `AsyncComponent` for data loading**

Use `AsyncComponent` whenever a component needs to fetch data before rendering. It handles loading and error states automatically — you only write the render path.

```scala
AsyncComponent[GetUserLogs.Response](
  fetch = () => props.ctx.api.client.getUserLogs,
  render = response => LogList(props.ctx, response),
  progressIndicator = () => Loader(props.ctx),
  watchedObjects = List(timesRefreshingData)   // re-fetches when this changes
)
```

- `fetch`: `() => Future[T]` — called once on mount (and again when `watchedObjects` changes)
- `render`: `T => ReactElement` — called only on success
- `progressIndicator`: always provide one; `() => Loader(props.ctx)` is the standard
- `watchedObjects`: pass a counter from `GenericHooks.useForceRefresh` to trigger manual refresh; omit if data never needs refreshing
- Error state: rendered automatically by the library; do not re-implement error rendering

@file: web/src/main/scala/net/wiringbits/components/widgets/Logs.scala  
@file: web/src/main/scala/net/wiringbits/components/widgets/UserInfo.scala

**RULE-FE-008 — `onComplete` for form submission**

When submitting a form, call the API and handle the result with `.onComplete`. Never use `.map`, `.flatMap`, or `.recover` for form submission side effects.

```scala
props.ctx.api.client.createUser(request).onComplete {
  case Success(_)  => setFormData(_.submitted)
  case Failure(ex) => setFormData(_.submissionFailed(ex.getMessage))
}
```

The full submit handler pattern:

```scala
def handleSubmit(e: SyntheticEvent[?, dom.Event]): Unit = {
  e.preventDefault()
  if (formData.isSubmitButtonEnabled) {
    setFormData(_.submit)
    for {
      request <- formData.data.submitRequest.orElse {
        setFormData(_.submissionFailed(texts.completeData))
        None
      }
    } yield props.ctx.api.client.doThing(request).onComplete {
      case Success(_)  => setFormData(_.submitted)
      case Failure(ex) => setFormData(_.submissionFailed(ex.getMessage))
    }
  }
}
```

Wrap inputs in an HTML `form` with `onSubmit` so the Enter key works. Never rely on button click alone.

```scala
form(onSubmit := (handleSubmit(_)))(
  // ... inputs, button with type="submit"
)
```

@file: web/src/main/scala/net/wiringbits/components/widgets/SignUpForm.scala

**RULE-FE-009 — Validated types and `FormData`**

Input models are validated primitives defined in `lib/common/shared/`. They extend `WrappedString` with a private constructor:

```scala
// Validate at boundary — returns ValidationResult
Email.validate("user@example.com")   // Right(Email(...)) or Left(error)

// Trust internal data (bypass validation — only in tests or server-side)
Email.trusted("user@example.com")
```

Each form has a `FormData` class (in `web/src/main/scala/net/wiringbits/forms/`) with:

- `fields: List[FormField[?]]` — all fields; used for validation errors
- `formValidationErrors: List[String]` — messages shown under the form
- `submitRequest: Option[Request]` — `Some` only when all fields are valid

State is wrapped in `StatefulFormData` which adds lifecycle:

```scala
val (formData, setFormData) = Hooks.useState(StatefulFormData(MyFormData.initial(...)))

setFormData(_.submit)                       // mark submitting (disables inputs + button)
setFormData(_.submitted)                    // mark done
setFormData(_.submissionFailed(message))    // re-enable, show error
setFormData(_.filling.copy(data = ...))     // update a field while user types
```

@file: web/src/main/scala/net/wiringbits/forms/SignUpFormData.scala  
@file: lib/common/shared/src/main/scala/net/wiringbits/common/models/Email.scala

**RULE-FE-010 — `.withKey()` for mapped lists**

Every element produced by a `.map` over a list must have a unique key:

```scala
items.zipWithIndex.map { case (item, i) =>
  MyRow(ctx, item).withKey(i.toString)
}
```

Omitting `.withKey()` causes React reconciliation warnings and can produce subtle rendering bugs when the list changes.

**RULE-FE-011 — Styling**

Prefer MUI theme tokens over hardcoded hex values:

```scala
// Preferred
js.Dynamic.literal(color = "primary.main", bgcolor = "background.paper")

// Avoid
js.Dynamic.literal(color = "#0E8C6F", bgcolor = "#FFFFFF")
```

Pseudo-selectors (`&:hover`, `&::after`) require `updateDynamic` on the literal because `js.Dynamic.literal` only accepts Scala identifiers as keys:

```scala
val btnSx: js.Object = {
  val obj = js.Dynamic.literal(color = "primary.main", fontWeight = 600)
  obj.updateDynamic("&:hover")(js.Dynamic.literal(background = "rgba(0,0,0,0.08)"))
  obj.asInstanceOf[js.Object]
}
```

Responsive breakpoint values use a nested literal:

```scala
js.Dynamic.literal(
  gridTemplateColumns = js.Dynamic.literal(xs = "1fr", sm = "1fr 1fr", md = "1fr 1fr 1fr")
)
```

@file: web/src/main/scala/net/wiringbits/components/widgets/AppBar.scala (navLinkActiveSx — complete example with ::after)

**RULE-FE-012 — `mui.Autocomplete` (type-ahead / combobox)**

MUI 5's `Autocomplete` requires a specific construction pattern because ScalablyTyped does not expose all its props via the fluent builder. Use `AutocompleteProps` + `js.Dynamic` mutation, then pass the typed ref to `withProps`:

```scala
import com.olvind.mui.muiMaterial.autocompleteAutocompleteMod.{AutocompleteProps, AutocompleteRenderInputParams}
import scala.scalajs.js.JSConverters.*

// Define outside the component body (no closures needed)
private def renderTextField(label: String)(params: AutocompleteRenderInputParams): ReactElement = {
  val dyn = params.asInstanceOf[js.Dynamic]
  dyn.label = label
  dyn.variant = "outlined"
  dyn.size = "small"
  (mui.TextField.outlined
    .withProps(dyn.asInstanceOf[com.olvind.mui.muiMaterial.textFieldTextFieldMod.OutlinedTextFieldProps])
    .withKey("tf"): ReactElement)
}

// Inside FunctionalComponent body:
val acProps = AutocompleteProps[MyOption, Boolean, Boolean, Boolean, "div"](
  options = myOptions.toJSArray,
  renderInput = renderTextField("My Label")
)
val dyn = acProps.asInstanceOf[js.Dynamic]
dyn.freeSolo = true       // allow free-text beyond the option list
dyn.size = "small"
dyn.disabled = isDisabled
dyn.inputValue = currentText  // controlled input value
// Extract getOptionLabel as a private[pages] def so it can be unit-tested directly.
// Ascribe opt as Any — Scala 3 rejects `case s: String` when scrutinee is js.Any
// (unrelated type hierarchies), but String and MyOption are both subtypes of Any.
// Do NOT use opt.asInstanceOf[js.Dynamic].name: Scala.js case class fields are
// name-mangled in JS output so .name returns undefined, which then crashes on cast.
private[pages] def myOptionLabel(opt: js.Any): String =
  (opt: Any) match {
    case s: String   => s
    case o: MyOption => o.name
    case _           => ""  // handles undefined/null/unexpected types safely
  }

dyn.getOptionLabel = (opt: js.Any) => myOptionLabel(opt)
dyn.onChange = (_: js.Any, value: js.Any, _: js.Any, _: js.Any) =>
  (value: Any) match {
    case o: MyOption =>
      setSelectedId(Some(o.id))
      // ... any auto-fill side effects
    case _ => ()
  }
dyn.onInputChange = (_: js.Any, value: js.Any, reason: js.Any) => {
  val text = if (js.typeOf(value) == "string") value.asInstanceOf[String] else ""
  val rsn  = if (js.typeOf(reason) == "string") reason.asInstanceOf[String] else ""
  setText(text)
  // Skip clearing id on "reset": onChange fires first and just set it
  if (rsn != "reset") setSelectedId(None)
}

(mui.Autocomplete.withProps(acProps).withKey("my-ac"): ReactElement)
```

Key rules:

- **Type parameters**: `AutocompleteProps[T, Boolean, Boolean, Boolean, "div"]` — the 5th must be the literal type `"div"`, not `String`.
- **`TextField` factory vs builder**: `mui.TextField.outlined.withProps(...)` — `outlined` without `()` is the factory; `outlined()` returns a builder that does **not** have `withProps`.
- **`js.Dynamic` mutation**: set all optional props (`freeSolo`, `getOptionLabel`, `onChange`, etc.) by mutating the JS object, then pass the original typed ref (`acProps`) to `withProps`.
- **`.withKey(k)` to render**: `withProps` returns a `Builder` AnyVal (just the props — NOT a React element). Call `.withKey(anyUniqueKey)` to materialise the actual React element via `KeyAddingStage`; then ascribe `: ReactElement` to trigger the implicit conversion. Using `.asInstanceOf[ReactElement]` on the Builder directly passes the raw props object to React and causes a runtime crash.
- **Controlled input**: pair `inputValue` with `onInputChange`. In `onInputChange`, skip `setSelectedId(None)` when `reason == "reset"` (MUI fires this after `onChange` selects an option — clearing the id there would undo the selection).
- **`freeSolo` + `onChange`**: with `freeSolo = true`, `onChange` fires with a plain string when the user presses Enter on typed text. Use `(value: Any) match { case o: MyOption => ...; case _ => () }` — this safely excludes strings, undefined, and null without needing `js.typeOf` guards.
- **`getOptionLabel` must use `(opt: Any) match`**: MUI calls `getOptionLabel` for every rendered list option and also with `undefined` when the dropdown first opens. Using `opt.asInstanceOf[js.Dynamic].name` crashes because Scala.js case class fields are name-mangled in JS output (`.name` returns `undefined`). Ascribing `opt` as `Any` and using a pattern match is the only correct approach. Extract this function as `private[pages]` so you can unit-test it directly with Merchant instances, plain strings, undefined, and null.

@file: web/src/main/scala/net/wiringbits/components/pages/BulkMerchantSetupPage.scala (DescriptionRow — real usage with merchant + category Autocomplete)

**RULE-FE-013 — Persist pageable/filterable state in the URL**

Any state that a user would want to bookmark or recover after a page refresh — pagination page, selected tab, filter value — must be stored in the URL query string, not only in React state. Local state alone is lost on refresh; the URL survives.

```scala
import net.wiringbits.webapp.utils.slinkyUtils.compat.RouterCompat
import net.wiringbits.webapp.utils.slinkyUtils.facades.reactrouterdom.useHistory
import org.scalajs.dom.URLSearchParams

// Inside FunctionalComponent body:
val history  = useHistory()
val location = RouterCompat.useTypedLocation()

// Read on mount (executed once because it's the useState initializer)
val initialPage = {
  val params = new URLSearchParams(location.search)
  val p = params.get("page")
  if (p == null) 0 else math.max(0, p.toIntOption.getOrElse(1) - 1)  // URL is 1-based
}
val (page, setPage) = Hooks.useState(initialPage)

// Write on change — update React state AND push to URL
def gotoPage(n: Int): Unit = {
  setPage(n)
  history.push(s"/my-page?page=${n + 1}")  // URL is 1-based for readability
}
```

Rules:

- Use 1-based page numbers in the URL (e.g., `?page=1`) — readable to users.
- Use 0-based numbers in React state — consistent with Scala `List.slice`.
- `RouterCompat.useTypedLocation().search` is always a `String`; `URLSearchParams` handles the leading `?` for you.
- `history.push` on every page change means the browser back button navigates through pages. Use `history.replace` if you want pagination not to pollute the history stack. `replace` uses the same signature as `push`.
- The `useState(initialPage)` call reads the URL once at mount. If the component stays mounted and the URL changes (e.g., browser back within the same route), state won't update automatically. For now this is acceptable — the component is re-mounted on a hard refresh, which is the main use case. Full back-button support would require a `useEffect` on `location.search`.

@file: web/src/main/scala/net/wiringbits/components/pages/BulkMerchantSetupPage.scala (live example — `gotoPage` and `initialPage`)

**RULE-FE-014 — `js.Dynamic` and `asInstanceOf` are banned from component bodies, pages, and widgets**

`js.Dynamic` and `asInstanceOf` casts bypass the Scala type system and make code brittle. They are **not permitted** inside component bodies, page objects, or widgets. The only approved location for these constructs is the `compat/` layer.

Use the approved compat entry points for all JS-interop needs:

| Use case | Approved helper |
|---|---|
| Pagination props | `MuiCompat.paginationProps(count, page, onChange)` |
| Autocomplete `renderInput` | `MuiCompat.textFieldProps(params, label, size)` |
| Autocomplete `onInputChange` text + reason | `MuiCompat.autocompleteInputChange(value, reason)` |
| Tabs `onChange` value | `MuiCompat.tabValue(v)` |
| JS value coercion (`js.Any` → `String`/`Double`) | `JsValueCompat.asString(v)` / `JsValueCompat.asDouble(v)` |
| Current route pathname and search | `RouterCompat.useTypedLocation()` |
| MUI theme construction | `ThemeCompat.themeOptions(colors, mode)` |

```scala
// CORRECT — call the compat helper; no js.Dynamic or asInstanceOf in the page
// currentPage is 0-based; MuiCompat.paginationProps converts to 1-based internally
mui.Pagination.withProps(MuiCompat.paginationProps(totalPages, currentPage, setPage))

// WRONG — js.Dynamic and asInstanceOf inside the render function
val dyn = js.Dynamic.literal(count = totalPages, page = currentPage + 1)
dyn.onChange = handler
mui.Pagination.withProps(dyn.asInstanceOf[PaginationProps])
```

If a new MUI prop pattern is not yet covered, add a helper to the appropriate compat object (`MuiCompat`, `JsValueCompat`, `RouterCompat`, or `ThemeCompat`) rather than inlining `js.Dynamic` in the caller.

**RULE-FE-015 — Reuse existing MUI components; do not re-implement**

Before writing a custom pagination control, table, dialog, or list, check whether MUI 5 already provides it (`mui.Pagination`, `mui.Table`, `mui.Dialog`, `mui.List`, etc.). Wrapping an existing MUI component is always preferred over reimplementing its functionality in Slinky.

```scala
// CORRECT — leverage mui.Pagination via the compat helper
mui.Pagination.withProps(MuiCompat.paginationProps(totalPages, currentPage, setPage))

// WRONG — hand-rolled "Previous / Next" buttons that duplicate MUI Pagination behavior
mui.Button.normal()("Previous").onClick(_ => setPage(p => math.max(0, p - 1)))
mui.Button.normal()("Next").onClick(_ => setPage(p => math.min(totalPages - 1, p + 1)))
```

Slinky component render tests for MUI wrappers are not required — they add maintenance cost without guarding against real runtime failures. Only add a render test when it protects against a known crash (see RULE-TE-010).

**RULE-FE-016 — Optional `ReactElement`: use `.fold[ReactElement](Fragment())`, not `.map().getOrElse()`**

When conditionally rendering from an `Option`, use `.fold[ReactElement](Fragment())(value => ...)`. The explicit type parameter drives the implicit conversion from any MUI builder to `ReactElement` without a manual coercion inside the lambda. Using `.map(value => ...).getOrElse(Fragment())` leaves the map body at the builder type, which can break incremental compilation.

```scala
// CORRECT
someOption.fold[ReactElement](Fragment())(value =>
  mui.Typography(value).sx(...)
)

// WRONG — map body stays as Typography.Builder; may fail to compile incrementally
someOption.map(value => mui.Typography(value).sx(...)).getOrElse(Fragment())
```

## Anti-patterns

See [antipatterns.md](antipatterns.md) §Frontend.

## Directory layout

```
web/src/main/scala/net/wiringbits/
  components/
    pages/      Full-page components (one per route)
    widgets/    Reusable sub-components and forms
  core/         ReactiveHooks, I18nHooks, MediaQueryHooks
  forms/        Form data models (StatefulFormData, validation)
  models/       AuthState, User, UserMenuOption
  services/     StorageService
  AppContext.scala
  AppRouter.scala
  AppTheme.scala
```
