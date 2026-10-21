---
description: The `document` table stores an uploaded file's identity and inferred attributes only — process/lifecycle state belongs in specialized per-type tables
files: (server/src/main/resources/evolutions/default/.+\.sql|server/src/main/scala/net/wiringbits/repositories/(daos/DocumentDAO|models/Document)\.scala)
---

`document` is the record of an uploaded file. It holds: identity (id, user_id),
the stored file (sha256, storage_key, filename), the declared `kind`, attributes
*inferred about the file* (ai_classification, ai_injection_*), and timestamps.

Flag a column added to `document` that is **process/lifecycle state** rather than
a fact about the file: processing status, parser/extraction version, parse
results or outputs, retry/attempt counts, OCR/analysis intermediate state. That
belongs in the specialized per-type table for the document (`receipt`,
`bank_statement_draft`), keyed by `document_id`.

Not: columns describing the file itself or what we infer about it; or an
evolution that *moves* such process state off `document` onto a specialized table.
