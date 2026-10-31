---
description: Frontend — fetch via AsyncComponent; mutually-exclusive UI state as one ADT; hook dependency lists via SafeHooks (String keys)
files: web/src/main/scala
---

Review changes under `web/src/main/scala`.

## Fetch data through AsyncComponent
Flag a component that loads data from the API without using `AsyncComponent` —
ad-hoc effects, state, and manual loading/error flags to fetch API data should
be an `AsyncComponent`, as the existing pages and widgets are.

Not: components that don't fetch from the API; effects unrelated to data loading.

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
