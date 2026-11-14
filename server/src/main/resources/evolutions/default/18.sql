-- !Ups

-- A recurring charge the user expects to pay. amount_min and amount_max bound the expected charge:
-- both NULL means the amount is unknown, equal values mean a fixed amount, and different values
-- describe a variable charge range.
CREATE TABLE subscription (
  id UUID NOT NULL,
  user_id UUID NOT NULL,
  name TEXT NOT NULL,
  cadence TEXT NOT NULL,
  interval_days INT NULL,
  amount_min NUMERIC(12,2) NULL,
  amount_max NUMERIC(12,2) NULL,
  payment_method TEXT NOT NULL,
  category_id UUID NULL,
  start_date DATE NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT subscription_id_pk PRIMARY KEY (id),
  CONSTRAINT subscription_user_id_fk FOREIGN KEY (user_id) REFERENCES users(user_id),
  CONSTRAINT subscription_category_id_fk FOREIGN KEY (category_id) REFERENCES category(id) ON DELETE SET NULL,
  CONSTRAINT subscription_user_id_name_unique UNIQUE (user_id, name)
);

CREATE INDEX subscription_user_id_index ON subscription USING BTREE (user_id);
CREATE INDEX subscription_category_id_index ON subscription USING BTREE (category_id);
