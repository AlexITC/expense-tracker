-- !Ups

-- Set-merchant rules can override the merchant's default category: merchant_override_category_id is that category for
-- set_merchant rules and NULL otherwise. It is separate from category_id (the set_category target, which cascades)
-- so deleting the override category only clears the override and keeps the rule.
ALTER TABLE merchant_rule
  ADD COLUMN merchant_override_category_id UUID NULL,
  ADD CONSTRAINT merchant_rule_merchant_override_category_id_fk FOREIGN KEY (merchant_override_category_id) REFERENCES category(id) ON DELETE SET NULL;

CREATE INDEX merchant_rule_merchant_override_category_id_index ON merchant_rule USING BTREE (merchant_override_category_id);

-- Set-merchant and set-category rules can carry a description for the created record (NULL means none).
ALTER TABLE merchant_rule
  ADD COLUMN description TEXT NULL;

-- A rule can also require an exact signed row amount (a charge is negative). NULL means any amount.
ALTER TABLE merchant_rule
  ADD COLUMN amount NUMERIC(12,2) NULL;

-- The unique indexes include the amount so a pattern+amount rule can coexist with the pattern-only rule.
-- NULLS NOT DISTINCT keeps two pattern-only (NULL amount) rules a conflict, as before.
DROP INDEX merchant_rule_ignore_unique;
CREATE UNIQUE INDEX merchant_rule_ignore_unique ON merchant_rule (user_id, pattern, amount) NULLS NOT DISTINCT
  WHERE action = 'ignore';

DROP INDEX merchant_rule_set_merchant_unique;
CREATE UNIQUE INDEX merchant_rule_set_merchant_unique ON merchant_rule (user_id, pattern, merchant_id, amount) NULLS NOT DISTINCT
  WHERE action = 'set_merchant';

DROP INDEX merchant_rule_set_category_unique;
CREATE UNIQUE INDEX merchant_rule_set_category_unique ON merchant_rule (user_id, pattern, category_id, amount) NULLS NOT DISTINCT
  WHERE action = 'set_category';
