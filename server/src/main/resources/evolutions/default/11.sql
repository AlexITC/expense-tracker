-- !Ups

-- DocumentKind and DocumentClass are now stored as their enum case names, replacing the former
-- snake_case (kind) and uppercase (ai_classification) forms.
UPDATE document SET kind = 'Receipt' WHERE kind = 'receipt';
UPDATE document SET kind = 'Invoice' WHERE kind = 'invoice';
UPDATE document SET kind = 'BankStatementPdf' WHERE kind = 'bank_statement_pdf';

UPDATE document SET ai_classification = 'BankStatement' WHERE ai_classification = 'BANKSTATEMENT';
UPDATE document SET ai_classification = 'PurchaseTicket' WHERE ai_classification = 'PURCHASETICKET';
UPDATE document SET ai_classification = 'BankVoucher' WHERE ai_classification = 'BANKVOUCHER';
UPDATE document SET ai_classification = 'Invoice' WHERE ai_classification = 'INVOICE';
UPDATE document SET ai_classification = 'Other' WHERE ai_classification = 'OTHER';
