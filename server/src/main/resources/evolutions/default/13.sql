-- !Ups

-- Whether the user confirmed the statement knowing its completeness could not be verified (no balances to reconcile).
ALTER TABLE bank_statement ADD COLUMN accepted_unverified BOOLEAN;
UPDATE bank_statement SET accepted_unverified = FALSE;
ALTER TABLE bank_statement ALTER COLUMN accepted_unverified SET NOT NULL;
