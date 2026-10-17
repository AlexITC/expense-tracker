-- !Ups

-- How a confirmed statement was extracted. NULL when unknown (statements created before this column, or
-- drafts with no recorded stage). Shape:
--   {"stage": "pdf_text" | "bda_markdown" | "textract_tables" | "bda_custom",
--    "model": "<model id>", "reconciled": true|false, "discrepancy": "<text>" | absent}
ALTER TABLE bank_statement ADD COLUMN extraction_info JSONB;
