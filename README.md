# Expense Tracker

A self-hosted expense tracker that turns receipts and bank statements into a clean, reconciled
ledger. Built in Scala 3 / Scala.js on top of the [wiringbits web-app template](https://github.com/wiringbits/scala-webapp-template).

Snap a receipt or upload a PDF bank statement and the app extracts the transactions, classifies and
matches them to your expenses, and verifies the numbers add up, the goal is to kee your ledger complete and
trustworthy with little manual entry.

## Features

- **Expenses** with categories (including split-across-categories), merchants, and refunds.
- **Import rules** to auto-assign a merchant or ignore noise as data comes in.
- **Receipt OCR** — upload a photo/PDF; AWS Textract extracts the amount, date, merchant and line
  items, and the parser picks the charged total (handling discounts, gift cards, taxes, FX).
- **Bank-statement import with AI extraction** — upload a PDF statement; the app extracts every
  transaction + metadata and **reconciles** them against the statement's own balances (running-balance
  chain, or opening ± Σ = closing). Robust to credit-card `CHARGES/CREDITS` columns, `CR` markers,
  foreign-currency rows, multiple cardholders, and header/footer noise. Extracted rows link back to
  your expenses so nothing is double-counted.
- **Savings goals** and allocations.
- **Telegram bot** — submit expenses and receipts from your phone.
- **MCP server** — an API token lets AI assistants query your data over the Model Context Protocol.
- Accounts, email verification, password recovery, and captcha (from the template).

AWS-backed features (Textract, Bedrock, Bedrock Data Automation, S3) default to **mock** mode, so the
app runs end-to-end locally with no cloud account; set them to `real` for production.


## Getting started

Requires JDK, sbt, Node, and PostgreSQL. See [docs/guide/local-setup.md](docs/guide/local-setup.md) for
the full setup, then:

```bash
sbt server/run     # backend  (http://localhost:9000)
sbt web/dev        # frontend (http://localhost:8080)
```

Database schema is managed by Play Evolutions and applied on startup. API docs (Swagger UI) are served
at `/api/docs/index.html`.

## Testing

```bash
sbt server/test    # backend (needs Docker for a throwaway Postgres)
sbt web/test       # frontend
```

See [docs/guide/testing.md](docs/guide/testing.md).

## Deployment

Ansible playbooks under `infra/` deploy to a managed server behind nginx. Admin API routes are intended
to sit behind the reverse proxy's HTTP basic auth — keep that in place.

## Credits

Bootstrapped from the [wiringbits Scala web-app template](https://github.com/wiringbits/scala-webapp-template).

## License

MIT — see [LICENSE](LICENSE).
