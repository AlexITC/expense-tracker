-- !Ups

-- How a confirmed statement was extracted: a JSON-serialized ExtractionInfo
-- (see net.wiringbits.api.models.bankstatements.ExtractionInfo). NULL when unknown.
ALTER TABLE bank_statement ADD COLUMN extraction_info JSONB;
