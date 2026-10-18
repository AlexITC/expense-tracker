-- !Ups

-- AI category/merchant suggestions for a confirmed statement's rows: a JSON-serialized AiSuggestions
-- (see net.wiringbits.api.models.bankstatements.AiSuggestions). NULL when not computed yet.
ALTER TABLE bank_statement ADD COLUMN ai_suggestions JSONB;
