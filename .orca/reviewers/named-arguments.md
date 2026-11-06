---
description: Name arguments at a call site when the callee has two or more parameters of the same type, so a swap cannot compile silently
---

Review the added/changed lines for the pattern below only. Report clear,
actionable cases with file and line; if none apply, report nothing.

## Pass same-typed arguments by name

When the method, function, or constructor being called declares two or more
parameters of the SAME type (anywhere in the signature, not only adjacent),
the call must pass those same-typed arguments by name.

Positional arguments that share a type can be swapped with no compile error and
no type error, silently passing the wrong values. The risk is highest for ids,
`String`s, and booleans. Example: `merge(survivorId, loserId, userId)` takes
three `UUID`s; swapping the first two still compiles and would delete the wrong
expense. `merge(survivorId = s, loserId = l, userId = u)` makes the binding
explicit and turns a swap into a visible diff.

Flag a call that passes positional arguments to a callee whose signature has
2+ parameters of one type, where those same-typed arguments are not named.
Naming only the same-typed arguments is enough; naming all of them is fine too.

Not:
- a call where every parameter has a distinct type, or the callee has fewer than
  two parameters of any one type;
- single-argument calls;
- infix/operator calls and lambda arguments, where names do not apply;
- varargs and collection/stdlib combinators (`map`, `zip`, `fold`, …) where
  naming is not idiomatic.

Future: this is a mechanical rule and is a candidate for a custom WartRemover
wart so the compiler enforces it; revisit then. Until that exists, it is checked
here at review time.
