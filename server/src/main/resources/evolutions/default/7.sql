-- !Ups

-- A receipt's processing status moves from the document onto the receipt itself: the document table only describes
-- the stored file, while pipeline state belongs to the entity being processed.
-- The column starts nullable so existing receipts can be backfilled from their document before NOT NULL is enforced.
-- Values are stored as the DocumentStatus case-object names ('Ingested', 'Parsed', ...).
ALTER TABLE receipt ADD COLUMN status TEXT;

UPDATE receipt r SET status = d.status FROM document d WHERE r.document_id = d.id;

ALTER TABLE receipt ALTER COLUMN status SET NOT NULL;

-- Processing state now lives on the per-type table (receipt.status, receipt.parser_version), so the document table
-- only describes the stored file.
ALTER TABLE document DROP COLUMN status;

ALTER TABLE document DROP COLUMN parser_version;
