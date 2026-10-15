# Test fixtures

These fixtures are **synthetic placeholders**. The original dataset was built from real
receipts and bank statements and was removed before open-sourcing (it contained PII:
merchant names, tax IDs, amounts, dates).

Every file here is a minimal, anonymous stand-in that keeps the fixture-driven specs
(`DocumentParserSpec`, `ParserAccuracyScoreSpec`) passing. To restore a real dataset:

- `textract/<n>.json` — a raw Textract `AnalyzeExpense` response; `tickets/<n>.json` — the
  exact parsed output `DocumentParser.parse` must produce for it. Add pairs indexed `1..N`
  and bump the loop range in `DocumentParserSpec`.
- `parser-accuracy/responses/<id>.json` — a raw Textract response; `parser-accuracy/labels.json`
  — the confirmed `amount`/`date` for each `id` (see `scripts.ParserAccuracyReport`).
