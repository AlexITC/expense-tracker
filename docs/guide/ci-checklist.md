# CI checklist

## Before every push

```bash
# 1. Format — CI will reject unformatted code
sbt scalafmtSbt   # formats build.sbt and project/*.sbt
sbt scalafmtAll   # formats all src/**/*.scala
git add -u        # stage any files scalafmt rewrote

# 2. Compile — catch errors and -Werror warnings
CI=true sbt Test/compile

# 3. Tests — backend requires Docker; frontend requires openssl flag
CI=true NODE_OPTIONS=--openssl-legacy-provider sbt test
```

If you only changed frontend code, `sbt "web/compile"` is sufficient to validate; see memory note in [frontend.md](frontend.md).

## What CI runs (pull_request.yml)

| Step | Command | Fails on |
|---|---|---|
| Format check | `sbt scalafmtCheckAll` | Any file not matching `.scalafmt.conf` |
| Compile | `CI=true sbt Test/compile` | Compiler errors **and warnings** (`-Werror` in CI) |
| Tests | `CI=true sbt test` | Any failing test, including testcontainers DB tests |
| Dev web health | `./scripts/test-dev-web.sh` | Webpack bundle doesn't start or serve |
| Web build | `sbt web/build` | Production bundle failure |
| Server dist | `sbt server/dist` | Production server packaging failure |

## Common CI failures and fixes

**"No warnings can be incurred under -Werror"**  
A Scala warning (unused import, deprecated syntax, etc.) is promoted to an error in CI. Fix the warning locally with `CI=true sbt Test/compile` before pushing.

**Deprecated `= _` for uninitialized vars**  
Use `import scala.compiletime.uninitialized` and `var x: T = uninitialized` instead of `var x: T = _`. See [testing.md](testing.md) §Exceptions.

**scalafmt diff**  
Run `sbt scalafmtSbt && sbt scalafmtAll` and stage the reformatted files. If your editor auto-formats on save, make sure it uses the same scalafmt version pinned in `project/plugins.sbt`.

**testcontainers timeout**  
Docker must be running before `sbt test`. PostgreSQL 13 is pulled on first run; subsequent runs use the cached image.

**`ERR_OSSL_EVP_UNSUPPORTED` (webpack / Node 24)**  
Set `NODE_OPTIONS=--openssl-legacy-provider` before running web tests. CI sets this automatically.

@file: .github/workflows/pull_request.yml  
@file: .scalafmt.conf
