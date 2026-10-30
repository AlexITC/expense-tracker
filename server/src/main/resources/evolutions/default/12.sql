-- !Ups

-- An expense can be linked to at most one bank statement row and at most one receipt.
CREATE UNIQUE INDEX bank_statement_row_expense_id_unique ON bank_statement_row (expense_id) WHERE expense_id IS NOT NULL;
CREATE UNIQUE INDEX receipt_expense_id_unique ON receipt (expense_id) WHERE expense_id IS NOT NULL;
