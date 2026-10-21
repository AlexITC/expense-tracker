-- !Ups

-- AI document classification: ai_classification is a DocumentClass entryName (BANKSTATEMENT, PURCHASETICKET,
-- BANKVOUCHER, INVOICE or OTHER) with its confidence, plus the advisory prompt-injection signal and its confidence.
-- NULL means the document was not classified.
ALTER TABLE document
  ADD COLUMN ai_classification TEXT NULL,
  ADD COLUMN ai_classification_confidence NUMERIC NULL,
  ADD COLUMN ai_injection_suspected BOOLEAN NULL,
  ADD COLUMN ai_injection_confidence NUMERIC NULL;
