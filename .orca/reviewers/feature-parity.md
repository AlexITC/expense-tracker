---
description: A change to one surface must reach every sibling surface handling the same concept, or the omission must be raised explicitly
---

Review the change for behavior added or altered on one code path while a sibling
path that handles the same concept is left untouched — and nothing in the diff
or PR description says that omission is intentional.

Flag a capability extended in one place (an action, endpoint, page, resolver, or
handler) when a parallel place handling the same concept was not updated to
match. Look beyond the diff for the sibling — search for other functions that
resolve, dispatch on, or render the same type/concept. The fix is to apply the
change to every such surface, **or** to state explicitly — in a code comment or
the PR description — which surface is left out and why.

Anchor example to calibrate: rules are resolved for both bank-statement rows and
receipts. A new rule action wired into the row resolver but not the receipt
resolver must either be wired into both or be marked as intentionally row-only.

Not: a concept that genuinely has a single surface; a surface where the behavior
does not apply (but say so, don't leave it silent); pure refactors that change no
behavior; or an omission already called out in the PR description or a comment.
