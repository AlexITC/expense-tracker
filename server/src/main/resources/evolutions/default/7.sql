-- !Ups

-- A receipt's processing status moves from the document onto the receipt itself: the document table only describes
-- the stored file, while pipeline state belongs to the entity being processed.
-- The column starts nullable so existing receipts can be backfilled from their document before NOT NULL is enforced.
-- Values are stored as the case-object names ('Ingested', 'Parsed', ...), matching document.status.
ALTER TABLE receipt ADD COLUMN status TEXT;

UPDATE receipt r SET status = d.status FROM document d WHERE r.document_id = d.id;

ALTER TABLE receipt ALTER COLUMN status SET NOT NULL;
