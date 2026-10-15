# Wiringbits Web App Template — Developer Guide

## Quick links
| Topic | File |
|---|---|
| Running locally | [docs/guide/local-setup.md](docs/guide/local-setup.md) |
| Frontend (Scala.js / MUI) | [docs/guide/frontend.md](docs/guide/frontend.md) |
| Backend (Play / Tapir) | [docs/guide/backend.md](docs/guide/backend.md) |
| Database & infra | [docs/guide/data-infra.md](docs/guide/data-infra.md) |
| Testing | [docs/guide/testing.md](docs/guide/testing.md) |
| CI checklist | [docs/guide/ci-checklist.md](docs/guide/ci-checklist.md) |
| Anti-patterns | [docs/guide/antipatterns.md](docs/guide/antipatterns.md) |

## Architecture in one paragraph
Tapir endpoint definitions live in `lib/api/shared/` and are shared by both sides.
The backend (Play, `server/`) wires them to `serverLogic` in controllers, which
delegate to thin action objects, which call Anorm repositories.
The frontend (Scala.js + React + MUI 5, `web/`) reads the same models and calls the
API via an `ApiClient` accessed through `AppContext`.

## Layer map
```
lib/api/shared/     Tapir endpoints + request/response models (shared)
lib/common/shared/  Validated primitives: Email, Name, Password, …
server/             Play backend: controllers → actions → repositories
web/                Scala.js frontend: pages → widgets, AppContext, AsyncComponent
infra/              Ansible playbooks for deployment
```

## Pre-push sequence — mandatory, in this exact order

1. `CI=true sbt Test/compile` — must succeed (compiles both main and test sources; catches test-module errors hidden by `sbt compile`)
2. `git add` all new and changed files
3. `sbt scalafmtAll scalafmtSbt` — run both; then `git add -u` to re-stage any reformatted files
4. `CI=true NODE_OPTIONS=--openssl-legacy-provider sbt test` — **run in foreground, never background**; do not proceed to step 5 until this exits with code 0
5. `git push origin master`

**Never run `git push` while tests are running in the background. Never push before confirming exit code 0.**

## Interaction rules — ask before coding

**Questions are not implementation requests.** If the user asks "isn't there a component for this?", "why did you do X?", or "what do you think?", respond in prose. Only write code after an explicit instruction to proceed.

**No hacks.** Do not use timing workarounds (`setTimeout` for event ordering), arbitrary flags, or brittle tricks. If no clean solution is obvious, say so and ask.

**No silent defaults for missing data.** When a parser or importer cannot determine a required value (year, currency, amount, etc.), it must fail loudly — throw an exception or return a `Left` — so the record is marked `Unsupported` rather than stored with a guessed value. Never fall back to `LocalDate.now()`, a hardcoded year, or any fabricated default without explicit user approval. Bad data in the ledger is worse than a failed import.

## Frontend reachability — mandatory rule

Every new page or view **must be reachable** from the running UI before the work is considered done. This means:

1. The route is registered in `AppRouter.scala` (both the `def` and the `Switch` call for authenticated routes)
2. There is at least one navigation entry point — a button, link, or menu item in an existing page that leads to the new route

If the route exists in `AppRouter` but nothing links to it, the feature is not done. Adding a page without a navigation entry point is a bug, not a partial delivery.

## Self-check — run through this after every feature

- [ ] Did I add or update tests for this change?
- [ ] Do my tests follow conventions in [docs/guide/testing.md](docs/guide/testing.md)?
- [ ] If I wrote a component test, did I use `BrowserRouter` and jsdom, and is `null.asInstanceOf[API]` only used when the component never calls `ctx.api`?
- [ ] Do all my new `val`s at object level contain only plain JS literals — no `KeyAddingStage` or `ReactElement`?
- [ ] Are any React elements reused in the same render tree? If so, did I use `def` instead of `val`?
- [ ] Do my DB queries include `WHERE user_id = ?` for every user-owned table?
- [ ] Did I put new Tapir endpoint definitions in `lib/api/shared/`, not in `server/`?
- [ ] Did I run `sbt scalafmtAll scalafmtSbt` and re-stage any reformatted files before pushing?
