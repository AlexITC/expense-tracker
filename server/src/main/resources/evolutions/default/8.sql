-- !Ups

-- The document (kind bank_statement_pdf) behind a draft import. The document is created at upload as Ingested and
-- outlives the draft: discarding a draft keeps the document, so a re-upload of the same file reuses it.
ALTER TABLE bank_statement_draft ADD COLUMN document_id UUID NOT NULL;
ALTER TABLE bank_statement_draft
  ADD CONSTRAINT bank_statement_draft_document_fk FOREIGN KEY (document_id) REFERENCES document(id) ON DELETE CASCADE;
CREATE INDEX bank_statement_draft_document_id_index ON bank_statement_draft USING BTREE (document_id);
