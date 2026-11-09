# Server architecture

The server exposes the same domain through four entrypoints: HTTP (Tapir
controllers), MCP, the Telegram bot, and background tasks. The architecture
exists to serve that fact:

> **One use case has one implementation, reachable by every entrypoint, and the
> database is reachable only through repositories.**

Layer direction is enforced at compile time by the Layering wart. The
authoritative "what may call what" graph is
[`server/.wartremover-layering.conf`](../../server/.wartremover-layering.conf);
this document explains the intent and names the packages. When the two disagree,
the conf wins.

## Layers and packages

| Layer | Package(s) | Role |
|---|---|---|
| **Entrypoints** (protocol-specific; translate protocol ⇄ core; no domain logic, no DB) | | |
| HTTP controllers | `controllers` | Tapir `serverLogic` wiring |
| MCP | `net.wiringbits.mcp` | MCP tools |
| Background jobs | `net.wiringbits.tasks` | scheduled / queued work |
| Ops scripts | `net.wiringbits.scripts` | one-off tooling |
| Router | `net.wiringbits.routing` | API router |
| DI | `net.wiringbits.modules` | Guice wiring |
| **Core** | | |
| Use cases | `net.wiringbits.actions` | one per operation; orchestrate; accept/return the contract; own no transaction |
| Guards | `net.wiringbits.validations` | read-only precondition checks at the action boundary |
| Shared logic | `net.wiringbits.services` | logic shared by 2+ actions; may have side effects; not a use case, not a transaction owner |
| **Persistence** | | |
| Repositories | `net.wiringbits.repositories` | one aggregate; owns its transaction (`withTransaction` over DAOs); returns `Future` |
| DAOs | `net.wiringbits.repositories.daos` | one table; raw SQL; synchronous |
| **External** | | |
| Clients | `net.wiringbits.apis` | outbound service clients (Telegram, Textract, SES, S3, Bedrock), behind traits with test fakes |
| **Data leaves** (each scoped to the layers that use it, not global) | | |
| DB row models | `net.wiringbits.repositories.models`, `net.wiringbits.repositories.support` | `dbmodels`: persistence + the action boundary only; unreachable from controllers, mcp, clients |
| External-client DTOs | `net.wiringbits.apis.models` | `clientmodels`: clients and their callers only |
| **Global leaf** (`shared`: usable everywhere; references only itself) | | |
| Contract | `net.wiringbits.api.models` (lib/api/shared) | request/response models shared with the web frontend and reused by MCP |
| Primitives | `net.wiringbits.common` (lib/common) | validated value types (`Email`, `Name`, `Password`, …) |
| Secret / enum value types | `net.wiringbits.models` | |
| Config | `net.wiringbits.config` | typed wrappers over Play config |
| Execution contexts | `net.wiringbits.executors` | |
| Helpers | `net.wiringbits.util` | stateless utilities |

## Rules the compiler cannot check

- **Models.** Actions accept and return the contract (`api.models`). Repository
  row models never cross the action boundary. Four model packages exist and must
  stay distinct: the contract (public, shared with the web app),
  `repositories.models` (DB rows), `apis.models` (external-client DTOs), and
  `net.wiringbits.models` (secret/enum value types). The first part is now
  compiler-enforced: DB rows (`dbmodels`) and client DTOs (`clientmodels`) are
  scoped leaves, so a controller or client touching a DB row is a build error.
- **Transactions.** A unit of atomicity is one repository method, using
  `withTransaction` over several DAOs. Never span a transaction across
  repositories; if two writes must be atomic they belong to one
  aggregate/repository method.
- **Entrypoints are thin.** They translate protocol and call the core. No domain
  logic, no repository access. A protocol that needs its own shape translates in
  its own entrypoint, never in the action.
- **Actions don't call actions.** Shared logic goes to a repository method or a
  service (see [backend.md](backend.md) for the concrete pattern).

## Target refinements (code changes; tracked for a parallel PR)

These require moving or renaming code, so they are not in the layering PR. None
change behavior; they tighten the structure further.

- **MCP mirrors HTTP.** HTTP is `routing` (router) → `controllers` (independent
  handlers) → actions, with the contract in lib/api. MCP crams the router
  (`McpDispatcher`), the handlers (`McpTools`, `McpBankStatementRows`), and the
  protocol types (`McpProtocol`) into one package, which is why `mcp` needs a
  self-edge. Split them so the tools are independent handlers and the self-edge
  goes away.
- **`PasswordHasher` → `util`.** It is a stateless helper, not shared business
  logic; it is the only reason `validations → services` exists. Move it, then
  drop that edge.
- **Remove the `apis` self-edge.** It only remains because `TextractClient` is a
  port whose companion holds result types its impls construct. Move those types
  to `clientmodels`, then drop `apis → apis`.
- **Scope the contract off persistence.** A few daos/repositories reference
  `api.models`; once removed, the contract can leave the global leaf.
- **Renames:** `apis` → `clients` (+ `apis.models` → `clients.models`);
  `net.wiringbits.api` (lib/api) → `contract`; `repositories.support` →
  `repositories.constraints`; and `net.wiringbits.models` is mislabeled (secret
  value types belong with `config`, enums are domain types).
- **`services` contents:** move stateless compute (parsing, serialization) to
  `util`; what remains in `services` is genuine shared logic.
- **Pure validation to the contract:** request-shape checks (amount, length)
  still inside `ExpenseValidator` can live in the shared contract/primitives so
  the frontend reuses them; stateful guards stay server-side.
