---
description: Repository/DAO patterns — per-user queries scoped to the user, reusable queries in DAOs, transactions for multi-call operations, conflict handling via DB constraints
files: server/src/main/scala/net/wiringbits/repositories
---

Review changes in the repositories/DAO package. Flag:

- A `SELECT` or `UPDATE` affecting a single user's rows without a
  `WHERE user_id = ...` bound to the current user. Every per-user read and write
  must be scoped to its user.
- A reusable query written inline in a repository that belongs in a DAO — DAOs
  hold the reusable queries, repositories compose them.
- `withConnection` used where several DB calls run together (writing multiple
  records, or read-then-update). Multiple calls that must succeed or fail as a
  unit belong in one `withTransaction`.
- A repository write that can violate a unique/exclusion constraint but does not
  translate that violation — leaving it to surface as a generic 500. The
  guarantee comes from the constraint, not an upstream check-then-write (which
  races), so the repository should let the write hit the constraint and
  `recover`/catch the `PSQLException`, **matched by the specific constraint or
  index name** — `ex.getMessage.contains("<name>_unique")` /
  `getServerErrorMessage.getConstraint` — returning the domain error the caller
  expects. Catching a `PSQLException`/`SQLException` **in the repository layer
  for this purpose is expected and not a finding**; a backing DB constraint must
  exist for the catch to guard anything.
- A write that references another user-owned entity (a merchant's category, a
  rule's merchant/category) that does not verify that entity's ownership here, in
  the same transaction as the write — the check (via the referenced entity's DAO,
  scoped to the same `user_id`) belongs in the repository so a cross-tenant
  reference can't be persisted by any path, not in the action. The target row's
  own existence/ownership comes from the write's `WHERE id = ? AND user_id = ?`
  (0 rows → "… not found"), so it needs no pre-`find`.

Not: single-call reads/writes under `withConnection`; queries over global /
non-user-owned tables; matching a violation by bare SQLState (e.g. `23505`)
where a constraint name is available; catching broad `Throwable`/`Exception`, or
swallowing a violation without surfacing an error.
