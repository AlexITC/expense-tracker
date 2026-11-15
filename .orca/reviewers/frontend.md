---
description: Frontend — fetch via AsyncComponent; drive async submit/save in-flight and error state through the shared useSubmit lifecycle; mutually-exclusive UI state as one ADT; state derived from its previous value set via the functional updater; hook dependency lists via SafeHooks (String keys); filters and view selections encoded in the URL; navigate with the router link, not hand-rolled anchors; reuse the MUI component (Autocomplete, Dialog, Menu, Pagination, …) instead of re-implementing it; a FormData model carries a spec; required fields modeled on the field (not Option, not hand-checked, submit gated on isValid); seed validated fields with the raw value; keep js.* interop inside the component; no hardcoded React keys in reusable components; pick-or-create through a shared resolver
files: web/src/main/scala
---

Review changes under `web/src/main/scala`.

## Fetch data through AsyncComponent
Flag a component that loads data from the API without using `AsyncComponent` —
ad-hoc effects, state, and manual loading/error flags to fetch API data should
be an `AsyncComponent`, as the existing pages and widgets are.

Not: components that don't fetch from the API; effects unrelated to data loading.

## Drive async submit/save in-flight and error state through useSubmit
Flag a component that runs an async submit or save — a `createX`/`updateX`/
`deleteX`/`confirm`/`assign`/`review` call on `ctx.api.client` — and tracks its
progress with hand-rolled `Hooks.useState`: a `saving`/`loading`/`submitting`/
`working`/`busy` boolean, an `error` `String`/`Option[String]`, or a local
save-state enum. Use the shared submission lifecycle
`net.wiringbits.ui.forms.SubmitHooks.useSubmit` instead. It returns a handle with
`isSubmitting`, `error`, and `begin()` / `succeed()` / `fail(msg)` / `reset()` /
`clearError()`: call `begin()` as the request starts, `fail(ex.getMessage)` in the
failure branch, `reset()` (back to editable) or `succeed()` on success; disable
inputs and the submit button with `isSubmitting` and render the error from
`error`. The giveaway is a failure branch that must remember to both set the error
and clear the in-flight flag (`setError(ex.getMessage); setSaving(false)`) — the
"forgot to clear → hung spinner" bug the hook makes impossible — or a
`disabled(saving)` plus error-`Typography` pair wired to those flags.

Not: forms built on the typed-form stack (`FormData`/`FormField`/
`StatefulFormData`) for validated text inputs, which carries its own lifecycle; a
richer local ADT that couples the lifecycle with other state it genuinely needs (an
editor that is also open/closed and holds a draft); a data-fetch loading flag,
which belongs in `AsyncComponent` (above); a plain non-async boolean toggle.

## Model mutually-exclusive UI state as one ADT, not parallel flags
Flag several `Hooks.useState`s that together encode a single state in which at
most one case holds — multiple `Option[...]` (or booleans) where setting one
must clear the others (e.g. which drawer/form is open), or a `progress + results`
pair that is really one `Idle → Running → Done` machine. Model it as one
`enum`/sealed ADT whose cases carry their own data, held in a single `useState`,
so the illegal combinations (two forms open at once, progress set with empty
results) cannot be represented and each transition is a single assignment. The
giveaway is a handler that sets one `useState` while resetting two or three
siblings in the same breath.

Not: states that can legitimately hold at the same time (independent toggles); a
lone flag with no sibling it excludes; the fetch loading/data/error triple, which
belongs in `AsyncComponent` (above).

## Derive state from its previous value with the functional updater
Flag a `useState` setter whose new value is computed from that same state's
current value read through the render closure — `setItems(items :+ x)`,
`setSelected(selected + id)`, `setCount(count + 1)`, `setChosen(chosen - id)`.
Use the functional updater form instead (`setItems(_ :+ x)`, `setSelected(_ + id)`,
`setCount(_ + 1)`), so React applies the change to the latest state rather than to
the value captured when the handler was created. The closure form silently drops
updates when several run before the next render (batched events, rapid clicks) or
when the setter fires from a stale async callback or effect.

Not: a setter whose new value does not come from that state's own previous value —
a constant, or a value taken from props or another state (`setOpen(true)`,
`setError(msg)`, `setMerchant(m)`); those cannot use the updater and need no change.

## Hook dependency lists go through SafeHooks (String keys)
Flag a component that calls `Hooks.useEffect` / `useMemo` / `useCallback` with a
dependency list directly — use `net.wiringbits.core.SafeHooks.{useEffect, useMemo,
useCallback}`, whose dependencies are `String` keys. React compares deps with
`Object.is`, which only compares by value for JS primitives; in Scala.js that is
`String`/`Int`/`Double`/`Boolean` only — NOT `Long` or `Char`, and no collection,
`Option`, tuple, case class, `UUID` or `BigDecimal` (objects compared by
reference, so a fresh instance each render re-fires the hook). A raw dependency
list that contains anything other than those four primitives — especially a
`List`/`Set`/`Map`/`Vector`, `Option`, case class, `UUID`, `Long` or `Char` — is
the bug SafeHooks exists to prevent; derive a `String` key
(`toString`, interpolation, `.map(_.toString).mkString`) instead.

Not: `Hooks.useEffect(fn)` with NO dependency argument (runs every render, by
design); `Hooks.useState` / `useRef` / `useContext` (no dependency list); the
low-level hook utilities in `net.wiringbits.core` (e.g. `ReactiveHooks`) that
deliberately key on a stable object reference.

## Encode filters and view selections in the URL
Flag a page that keeps a user-chosen filter or view selection — an account or
category picker, a search box, a status filter, a selected tab, a sort — only in
component state, so a refresh, a shared link, or back-navigation loses it. Such
state must be read from the URL (path or query params) on mount and written to
the URL when it changes, so the page is refreshable, shareable, and
back-navigable, and the local state must re-sync when the URL changes while the
component stays mounted (back/forward between two entries, a nav-link click, a
pasted link), so the control never disagrees with the address bar. Values put in
the query must be URL-encoded and decoded, not concatenated raw.

Not: ephemeral UI state that is not a filter or selection (a drawer open/closed,
a hover, an unsaved form draft, a loading flag); transient state that should not
survive a refresh by design.

## Navigate to routes with the router link, not a hand-rolled anchor
Flag navigation to an in-app route built from a click handler instead of the
router link component (`reactrouterdom.NavLink`). The giveaway is an element whose
`onClick` calls `history.push` or `dom.window.open` to go to a route, especially
one that inspects the mouse button or modifier keys (`button`, `ctrlKey`,
`metaKey`, `shiftKey`, `altKey`) to decide between in-app navigation and a new
tab. That is reimplementing what an anchor does for free: a real `<a href>` (which
`NavLink` renders) already gives the hover URL preview and native ctrl/cmd/shift/
middle-click behavior, with a plain click doing in-app navigation. Use `NavLink`
with `to`, put any action buttons outside it (not nested inside the anchor), and
delete the modifier-key handling.

More generally, do not read keyboard-modifier or mouse-button state to re-create
default browser behavior. Inspecting which keys are pressed is legitimate only
when building an actual keyboard shortcut or a deliberate custom interaction, not
to patch up an element that should have been a link or a button.

Not: `history.push` from a genuine action handler that is not a link (a form
submit, a button that acts then navigates, a redirect after an API call); reading
modifier keys inside a real keyboard-shortcut handler.

## Reuse the MUI component instead of re-implementing it
Flag a widget hand-built from primitives (`Box`, `TextField`, `onClick`,
state flags) when MUI already provides it — a type-ahead/combobox, a dialog, a
menu, pagination, a table, tabs. Reach for the MUI component (`mui.Autocomplete`,
`mui.Dialog`, `mui.Menu`, `mui.Pagination`, `mui.Table`, `mui.Tabs`) and
configure it; a reimplementation diverges from the real component's behaviour,
accessibility, and keyboard handling, and has to be maintained by hand.

The clearest case is a type-ahead built as a `TextField` paired with an `*Open`
boolean and a floating `Box`/`List` of options the user clicks to pick a value
(the giveaway is a manual `options.filter(_.name.toLowerCase.contains(input))`
with a `.take(n)`, an `onClick` per item, and no keyboard handling). That silently
drops what `mui.Autocomplete` gives for free and users expect: the full scrollable
option list, arrow-key navigation, Escape closing only the list (a hand-rolled one
lets Escape bubble up and close the whole dialog), and correct Tab focus.

Not: a search box that merely filters an already-visible list in place (it narrows
what is rendered, it does not pop a dropdown of options that returns a chosen
value); a fixed, short, known set of choices, which is a `mui.Select`/`mui.MenuItem`
(or an `enum`-backed select), not a type-ahead; a genuinely bespoke widget MUI has
no equivalent for.

## A FormData model carries a spec
A `FormData` subclass holds the form's logic — `submitRequest`, the field list,
and any transition such as a kind switch — all of it pure and testable without a
browser. Flag a new or changed `FormData` under `net/wiringbits/forms` whose logic
is added or altered with no matching `*FormDataSpec` added or updated (see
`SignInFormDataSpec`). The spec exercises `submitRequest` (a request when the data
is valid, `None` when a required field is missing or any field is invalid) and any
transition the model exposes. It is a plain ScalaTest spec: no jsdom, no component.

Not: a component (a `*Spec` for rendering belongs with the component, and much of
it needs jsdom); a `FormData` change that is a pure rename or field reorder with no
behaviour change.

## Required fields are modeled on the field, not re-checked by hand
A required input is a `FormField(required = true)` whose value type is the
primitive, so a blank is invalid; `FormData.isValid` already reports a required
field left empty (even untouched), and the submit button is disabled through it
(`useSubmit`'s `buttonDisabled(formData.isValid)`). Flag:
- a required field typed `Option[...]` (the `Option` is for genuinely optional
  fields only), and
- a hand-written required check in `formValidationErrors` such as
  `Option.when(field.valueOpt.isEmpty)("… is required")` that re-expresses what
  `isValid` already enforces.

Not: an optional field held as `Option` (correct); a form-level cross-field rule
that no single field can express (a captcha, "end after start"), which legitimately
lives in `formValidationErrors`.

## Seed validated fields with the raw value
When a `FormData.initial`/edit-seed fills a validated `FormField` from source data
(a bank row, an API model), pass the value as-is and let the field's validator
surface an over-limit or malformed value, so the user sees it and chooses what to
change. Flag a `.trim` or `.take(limit)` applied to a value only to squeeze it into
a validated field — that silently discards or truncates the user's data instead of
flagging it.

Not: trimming at submit as part of real normalization the API expects; a value that
is not seeding a validated field.

## Keep JS interop inside the component; its Props stay plain Scala
A component that wraps a ScalablyTyped/MUI widget bridges its loosely-typed
callbacks internally — narrowing the `js.Any` a callback hands back (a `ClassTag`
plus a match on the concrete option type, never `js.Dynamic` field access on a
Scala.js case class), and interpreting MUI's event-reason strings. Flag `js.Any`
or `js.Dynamic` in a component's public `Props`, or a raw MUI reason string (e.g.
`"reset"`) leaking to the caller that configures the component. The caller should
pass plain typed functions/values; the `js.*` stays inside.

Not: `js.*` inside the component's own body and the `MuiCompat` helpers (where the
interop belongs); a thin typed facade value passed in.

## Don't hardcode a React key in a reusable component
A `withKey` inside a reusable input/widget must derive from its props (the field
name, an id), not a string literal — two instances of the same component rendered
as siblings would otherwise share a key and collide. Flag a literal
`.withKey("…")` in a shared component.

Not: a caller choosing keys for the specific siblings it lays out at one use site
(that is the caller's responsibility, and distinct per site).

## Resolve pick-or-create through a shared resolver
A field that lets the user pick an existing entity or type a new one resolves to an
id the same way everywhere: the picked id, else an existing row matched by
case-insensitive name, else a freshly created one. Flag a form that inlines that
`list.find(_.name.equalsIgnoreCase(x)) … else client.createX(...)` dance — route it
through the shared resolver (`CategoryResolver`, `MerchantResolver`) so the create
path and its validated input live in one place, not re-implemented per form.

Not: a lookup that only finds (never creates); resolving something that is not a
user-entered entity selection.
