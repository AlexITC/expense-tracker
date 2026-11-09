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
| **Shared leaf** (data + infra; references only itself) | | |
| Contract | `net.wiringbits.api.models` (lib/api/shared) | request/response models shared with the web frontend and reused by MCP |
| Primitives | `net.wiringbits.common` (lib/common) | validated value types (`Email`, `Name`, `Password`, …) |
| DB row models | `net.wiringbits.repositories.models` | rows returned by DAOs |
| Constraint identifiers | `net.wiringbits.repositories.support` | |
| External-client DTOs | `net.wiringbits.apis.models` | |
| Secret / enum value types | `net.wiringbits.models` | |
| Config | `net.wiringbits.config` | typed wrappers over Play config |
| Execution contexts | `net.wiringbits.executors` | |
| Helpers | `net.wiringbits.util` | stateless utilities |

## Rules the compiler cannot check

- **Models.** Actions accept and return the contract (`api.models`). Repository
  row models never cross the action boundary. Four model packages exist and must
  stay distinct: the contract (public, shared with the web app),
  `repositories.models` (DB rows), `apis.models` (external-client DTOs), and
  `net.wiringbits.models` (secret/enum value types).
- **Transactions.** A unit of atomicity is one repository method, using
  `withTransaction` over several DAOs. Never span a transaction across
  repositories; if two writes must be atomic they belong to one
  aggregate/repository method.
- **Entrypoints are thin.** They translate protocol and call the core. No domain
  logic, no repository access. A protocol that needs its own shape translates in
  its own entrypoint, never in the action.
- **Actions don't call actions.** Shared logic goes to a repository method or a
  service (see [backend.md](backend.md) for the concrete pattern).

## Target refinements (not yet applied; none required for correctness)

The current names mostly match their roles. The following are naming/placement
cleanups that strict adherence implies. They change no behavior and are not
blockers; they are recorded so the target is explicit.

- `apis` → `clients` (and `apis.models` → `clients.models`): these are outbound
  clients, and the name collides with the contract.
- `net.wiringbits.api` (lib/api) → `contract`: it is the contract actions speak,
  currently one letter from `apis.models` (the opposite meaning).
- `net.wiringbits.models` is mislabeled (it holds secret value types plus enums):
  the secrets belong with `config`; the enums are domain types.
- `repositories.support` → `repositories.constraints` (it holds only constraint
  identifiers).
- `services` contents: move stateless compute (parsing, serialization) to `util`;
  the storage port and its adapters belong with the external clients. What
  remains in `services` is genuine shared logic.
- Pure, request-shape validation (e.g. amount and length checks still inside
  `ExpenseValidator`) can live in the shared contract/primitives so the frontend
  reuses the same rules; stateful guards stay server-side.
