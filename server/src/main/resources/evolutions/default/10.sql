-- !Ups

-- Import rules can assign a category: category_id is the target category for set_category rules
-- and NULL for every other action. Deleting the category deletes its rules.
ALTER TABLE merchant_rule
  ADD COLUMN category_id UUID NULL,
  ADD CONSTRAINT merchant_rule_category_id_fk FOREIGN KEY (category_id) REFERENCES category(id) ON DELETE CASCADE;

CREATE INDEX merchant_rule_category_id_index ON merchant_rule USING BTREE (category_id);

CREATE UNIQUE INDEX merchant_rule_set_category_unique ON merchant_rule (user_id, pattern, category_id)
  WHERE action = 'set_category';
