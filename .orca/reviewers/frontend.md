---
description: Frontend data fetching goes through AsyncComponent; mutually-exclusive UI state is one ADT, not parallel flags
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
