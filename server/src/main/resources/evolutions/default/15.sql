-- !Ups

-- A user-chosen nickname for a bank account, identified by the same tuple statements use to group accounts.
-- An empty alias means the user has not named the account.
CREATE TABLE bank_account_alias(
  id UUID NOT NULL,
  user_id UUID NOT NULL,
  bank_name TEXT NOT NULL,
  product_type TEXT NOT NULL,
  account_last4 TEXT NOT NULL,
  currency TEXT NOT NULL,
  alias TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT bank_account_alias_id_pk PRIMARY KEY (id),
  CONSTRAINT bank_account_alias_user_id_fk FOREIGN KEY (user_id) REFERENCES users(user_id),
  CONSTRAINT bank_account_alias_user_account_unique UNIQUE (user_id, bank_name, product_type, account_last4, currency)
);

CREATE INDEX bank_account_alias_user_id_index ON bank_account_alias USING BTREE (user_id);
