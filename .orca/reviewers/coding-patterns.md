---
description: Scala coding patterns — Chimney for case-class mapping, no dropped Futures, no silent defaults for missing data
---

Review the added/changed lines for the patterns below only. Report clear,
actionable cases with file and line; if none apply, report nothing.

## Use Chimney for case-class mapping
Flag a case class built from another by hand (`Target(a.x, a.y, ...)` or
`Target(x = src.x, ...)`) when the fields line up by name — use
`src.transformInto[Target]` / `src.into[Target].transform` instead. Not:
mappings that are mostly computation, or where most fields need custom handling.

## Never drop a Future
Flag a `Future[_]` in statement position whose value is neither returned,
awaited, assigned, nor composed into the result — it silently loses its errors
and ordering. Not: a Future that is returned, used, or chained in.

## No silent defaults for missing required data
Flag a parser/importer that invents a required value it cannot determine —
`LocalDate.now()`, a hardcoded year, `0` for a missing amount, a default
currency, `getOrElse(<guess>)`. It must fail (`Left`/throw) so the record is
rejected. Not: a default for a genuinely optional field.

## Prefer enums over stringly-typed values
Flag a `String` (or bare `Int` code) used to represent a fixed, known set of
values — a status, kind, type, mode — where a Scala `enum`/sealed trait should
model it. Not: genuinely free-form text (names, descriptions, external ids).

## Each variant carries its own data — no tag plus parallel options
Once a variant implies specific data, attach that data to the variant (a
parameterized Scala 3 `enum` case, e.g. `case SetMerchant(merchantId: UUID)`)
instead of pairing a bare tag with sibling `Option`/nullable fields that are
only valid for some variants. The giveaway is validation that reads like "if the
tag is X then field Y must be present (or absent)" — `SetMerchant` with no
merchant, `Ignore` with a category. Flag a type that holds a fixed variant tag
beside such conditionally-valid optional fields, and model it so the illegal
combinations cannot be constructed; the write path to flat storage columns then
becomes a total mapping and those validations and their tests disappear. Not: an
`Option` that is genuinely optional across every variant, or a lone optional
field with no variant tag governing it.

## Dispatch on a variant with an exhaustive match, not equality
Flag `== SomeVariant`, `filter(_.x == SomeVariant)`, `collect`, or `contains`
used to **select behavior, or select which items a variant applies to**, when
branching on an `enum`/sealed type — a `match` turns a newly added variant into
a compile error instead of a silently skipped case. Not: a single local boolean
guard where only one variant is genuinely of interest and a new variant should
not change it (e.g. `if (rule.action == Ignore) skip`); this targets
dispatch/selection, not every equality check.

## Correctness invariants live at the database, not in a check-then-write
A uniqueness, exclusion, or referential invariant must be guaranteed by a DB
constraint — not by an application read-then-write that checks the value looks
free and then inserts. That check-then-write races under concurrency: two
requests both pass the check and both write, so it only narrows the window, it
never closes it. Flag such a pre-check — both when it is relied on as the
guarantee, and when it merely sits on top of a constraint, where it is redundant
dead weight that duplicates (and will drift from) the real error. Both the
guarantee and the user-facing error come from catching the constraint's
violation **at the repository boundary** (see persistence) and returning the
domain error — not caught in an action/controller, where the `PSQLException` has
leaked up a layer. Not: validating a value's shape or content (length, format,
range) that no constraint expresses; logic with a single writer / no concurrent
writes.

## Ownership and existence are enforced at the write, in the repository
Multi-tenant / ownership integrity is a repository responsibility (see
architecture, persistence), not an action's — it is how a cross-tenant or
dangling reference is kept from ever being persisted. For a write:
- the **target** row's existence/ownership comes from the DAO's
  `UPDATE/DELETE … WHERE id = ? AND user_id = ?` (0 rows → "… not found"), not a
  pre-`find`;
- a **referenced** user-owned entity (a merchant's category, a rule's
  merchant/category) has its ownership checked inside the repository method,
  before the write and in the same transaction.

Flag an action that pre-`find`s a row only to raise a not-found/ownership error,
or that checks a referenced entity's ownership before calling the repository —
both belong in the repository. Not: input/shape validation (length, format,
range) and non-integrity business rules, which stay in the action; a pre-read
whose row is actually used for its data; and the deliberate find-then-delete
convention.

## Comments explain intent, not the change
A comment must tell a reader something the code can't — not narrate the diff for
a reviewer. Flag a comment that either:
- describes the change or history — "now", "moved to", "used to", "previously",
  "no longer", "instead of"; or
- restates another component's behavior, especially by name (e.g. "enforced in
  `SomeRepository.create`") — it duplicates logic that lives elsewhere and goes
  stale the moment that code moves.
Those belong in the PR description, not the source. Not: a comment that documents
non-obvious *why*/intent a reader genuinely needs; a warning about a surprising
coupling or edge case; a `TODO`/`FIXME`; a scaladoc describing the member it is
attached to.

## Log user-scoped operations with the user id
Flag a `logger.*` statement describing an operation on a specific user's data
that omits the user id when a `userId` is in scope (or trivially derivable, e.g.
from a loaded entity) — include `userId=$userId` so the log is attributable, and
log it only after the user is authenticated/resolved, never before. Not: genuine
system/background logs with no user in scope (startup, an unauthenticated webhook
before the user is identified, a purely entity-keyed message where no userId is
available).
