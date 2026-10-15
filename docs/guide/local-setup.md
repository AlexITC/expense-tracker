# Local setup guidelines

## Prerequisites

| Tool | Version | Install |
|---|---|---|
| JDK | 25.0.3-tem | `sdk install java 25.0.3-tem` (SDKMAN) |
| SBT | (latest 1.x) | `sdk install sbt` |
| Node.js | 24 | `nvm install` (reads `.nvmrc`) |
| Yarn | (any) | `npm install -g yarn` |
| Docker | (any) | Required for integration tests |
| PostgreSQL | 13+ | Local install or Docker |

SDKMAN and NVM are managed via `.sdkmanrc` and `.nvmrc`. Run `sdk env && nvm use` from the repo root before any `sbt` command.

## Database setup

```sql
CREATE DATABASE wiringbits;
\c wiringbits
CREATE EXTENSION citext;
```

Environment variables (`.envrc` with direnv, or export manually):

```
POSTGRES_HOST=localhost
POSTGRES_DATABASE=wiringbits
POSTGRES_USERNAME=postgres
POSTGRES_PASSWORD=postgres
EMAIL_SENDER_ADDRESS=dev@example.com
EMAIL_PROVIDER=console          # prints emails to stdout, no real sending
RECAPTCHA_SECRET_KEY=test
RECAPTCHA_SITE_KEY=test
```

Play Evolutions run automatically on server start (`play.evolutions.autoApply = true`).

## Starting the app

```bash
# Terminal 1 — backend (auto-reloads on save)
sbt server/run

# Terminal 2 — frontend dev server (hot reload, proxies API to :9000)
sbt dev-web
```

Frontend: http://localhost:8080  
Backend API: http://localhost:9000  
Swagger UI: http://localhost:9000/docs/swagger-ui/index.html

## Running tests

```bash
# All tests (requires Docker for integration tests)
CI=true NODE_OPTIONS=--openssl-legacy-provider sbt test

# Backend only
CI=true sbt server/test

# Frontend only
NODE_OPTIONS=--openssl-legacy-provider sbt "web/test"

# Single spec
sbt "testOnly *UsersRepositorySpec"
```

`NODE_OPTIONS=--openssl-legacy-provider` is needed because Node 24 + webpack 5 use a hash that triggers OpenSSL legacy mode. CI sets this via env automatically.

## Production builds

```bash
sbt web/build      # produces webpack bundle
sbt server/dist    # produces deployable Play dist zip
```

@file: server/src/main/resources/application.conf — all config keys with environment variable overrides  
@file: .sdkmanrc — canonical JDK version  
@file: .nvmrc — canonical Node version  
@file: docs/setup-dev-environment.md — extended setup notes
