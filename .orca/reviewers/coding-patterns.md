---
description: Scala coding patterns — Chimney for case-class and sum-type mapping, no dropped Futures, no silent defaults for missing data, avoid default parameter values, no read-compat shims for regenerable persisted data, enums over stringly-typed values
---

Review the added/changed lines for the patterns below only. Report clear,
actionable cases with file and line; if none apply, report nothing.

## Use Chimney for case-class and sum-type mapping
Flag a case class built from another by hand (`Target(a.x, a.y, ...)` or
`Target(x = src.x, ...)`) when the fields line up by name — use
`src.transformInto[Target]` / `src.into[Target].transform` instead.

This applies to **sum types** too: a `match` that maps one `enum`/sealed type to
another whose cases correspond by name (`case Foo => Other.Foo; case Bar =>
Other.Bar; ...`) is a hand-rolled transformation — use
`src.transformInto[Other]`, which Chimney derives case-by-name and turns a new
unmapped case into a compile error. A common giveaway is a domain↔DTO bridge
method such as `toApi`/`fromApi`. Frontend and backend deploy together and there
are no third-party API clients, so keeping such a type (or its conversion) solely
to pin a wire representation is not worth the boilerplate — map with Chimney, or
change the representation.

Not: mappings that are mostly computation, that rename or combine fields/cases,
or where the cases/fields do not line up by name.

## Never drop a Future
Flag a `Future[_]` in statement position whose value is neither returned,
awaited, assigned, nor composed into the result — it silently loses its errors
and ordering. Not: a Future that is returned, used, or chained in.

## No silent defaults for missing required data
Flag a parser/importer that invents a required value it cannot determine —
`LocalDate.now()`, a hardcoded year, `0` for a missing amount, a default
currency, `getOrElse(<guess>)`. It must fail (`Left`/throw) so the record is
rejected. Not: a default for a genuinely optional field.

## Avoid default parameter values
Per `docs/design-decisions.md` ("Avoid default parameter in most cases"), a
default value silently swallows a mistake that would otherwise be a compile
error: a Chimney transform whose field was renamed, or a call site that forgot
the new field, takes the default instead of failing to compile. Flag a default
parameter value added to a domain/data model's constructor or to a function
parameter that carries meaning — anything branched on, persisted, or produced by
computation (e.g. a verification output like `residual`/`unverifiable`). Dropping
the default makes every construction explicit and lets the compiler force each
site to be reconsidered when the field is added. A default that biases to the
empty/`false`/`None` value is the worst case, since an omission then fails
silently and in the less-safe direction.

Not: a default in the HTTP request/response wire layer, where defaulting a new
parameter is the documented way to keep older callers working
(`docs/design-decisions.md`); a default on test code (a fixture or helper under
`src/test`), where terse construction is the point and no production behavior
rides on it; a genuinely optional ergonomic knob whose default is unambiguously
safe and drives no logic; or a two-phase lifecycle field filled in later by a
known producer.

## No read-compat shims for regenerable persisted data
A model serialized into a stored JSON column (e.g. `bank_statement_draft.payload`)
must derive its format normally (`Json.format`). Flag a hand-written `Reads`
whose only job is to default a newly added field for rows written before it
existed (`readWithDefault`, `readNullable.map(_.getOrElse)`), usually with a
comment about old/legacy records. Frontend and backend deploy together and such
data is short-lived and regenerable (a draft is deleted at confirm and can be
re-uploaded), so the few stale rows are dropped or re-created, not read-shimmed.

Not: durable data that cannot be regenerated — that needs a real
evolution/backfill, not a read default; and not a genuinely optional field
(`readNullable` into an `Option`), which is normal and not a compat shim.

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
