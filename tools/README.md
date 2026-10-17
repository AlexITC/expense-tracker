# Bank-statement test data

`gen_test_statements.py` generates synthetic bank statements for testing statement
extraction. No PII — invented bank names, cardholders and merchants, in Spanish and
English. For each statement it writes a printable HTML document and the ground-truth
JSON the extractor must reproduce, both rendered from one canonical transaction list
so they always agree.

## Run

```sh
python3 tools/gen_test_statements.py --out ./out --lang both --seed 42
```

| Flag | Default | Meaning |
|---|---|---|
| `--out` | _(required)_ | Output directory. |
| `--lang` | `both` | `es`, `en`, or `both`. |
| `--seed` | `42` | RNG seed; a fixed seed gives identical output every run. |

Writes `out/<lang>/<name>.html` and `out/<lang>/<name>.json`. Pure standard library —
no packages to install.

## Render to PDF (optional)

The HTML and JSON need nothing extra. To feed a real extractor you render the HTML to
PDF (A4) yourself:

```sh
python3 -m weasyprint out/en/charge_card.html charge_card.pdf   # needs weasyprint
# or open the .html in a browser and Print -> Save as PDF
```

## Outputs

### `<name>.html`

A printable A4 statement laid out like a real one (summary box, transaction table,
per-page header/footer, fine print). This is what you render to PDF and feed to the
extractor or OCR.

### `<name>.json`

The exact data the extractor must produce from that statement:

```json
{
  "metadata": {
    "bankName": "SUMMIT EXPRESS",
    "productType": "credit",
    "currency": "USD",
    "accountLast4": "1234",
    "periodStart": "2026-01-12",
    "periodEnd": "2026-04-01",
    "openingBalance": "1103.37",
    "closingBalance": "7305.13"
  },
  "transactions": [
    {
      "date": "2026-01-13",
      "amount": "-75.80",
      "description": "NORTH FUEL STATION  NEW YORK",
      "runningBalance": null,
      "type": "purchase"
    }
  ],
  "reconciled": true
}
```

Conventions:

- Every monetary field — `amount`, `openingBalance`, `closingBalance`,
  `runningBalance` — is a **2-decimal, dot-separated string** (`"-75.80"`),
  independent of language. Only the rendered HTML uses the ES comma / EN dot.
- `amount` is **signed**: negative = money out (charge/debit), positive = money in
  (payment/credit/refund).
- `type` is one of `purchase`, `payment`, `fee`, `interest`, `refund`.
- `runningBalance` is set only when the statement prints a per-row balance (debit
  account); otherwise it is `null`.
- Reconciliation always holds: `openingBalance` ± `sum(amount)` = `closingBalance`
  (sign-agnostic), or the per-row running-balance chain.

## Archetypes

| `<name>` | product | Parsing hazards it reproduces |
|---|---|---|
| `charge_card` | credit | `CR` credit marker; FX rows whose rate/fee live in the description and whose printed total already includes the fee; the foreign amount shown **inline in a column on some rows and on a wrapped continuation line on others**; two interleaved cardholders. |
| `credit_card` | credit | Separate CHARGES/CREDITS columns where the sign comes from the **column, not the wording**; pseudo balance rows; per-page header "noise" blocks. |
| `debit_account` | debit | Signed amounts with a running balance; dates with **no year** (the year is only in the period header). |

The Spanish and English variants additionally differ in number format (comma vs dot
decimal) and label wording in the HTML.
