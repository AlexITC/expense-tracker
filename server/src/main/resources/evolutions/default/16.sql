-- !Ups

-- Set-merchant rules can override the merchant's default category: override_category_id is that category for
-- set_merchant rules and NULL otherwise. It is separate from category_id (the set_category target, which cascades)
-- so deleting the override category only clears the override and keeps the rule.
ALTER TABLE merchant_rule
  ADD COLUMN override_category_id UUID NULL,
  ADD CONSTRAINT merchant_rule_override_category_id_fk FOREIGN KEY (override_category_id) REFERENCES category(id) ON DELETE SET NULL;

CREATE INDEX merchant_rule_override_category_id_index ON merchant_rule USING BTREE (override_category_id);

-- Set-merchant and set-category rules can carry a description for the created record (NULL means none).
ALTER TABLE merchant_rule
  ADD COLUMN description TEXT NULL;
