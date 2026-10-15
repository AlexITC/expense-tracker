-- !Ups

-- Consolidated app schema. Evolutions 3..18 were squashed into this single step. 1.sql and 2.sql
-- remain the template base. This creates every surviving table in its final shape directly, so a
-- fresh database reaches the current schema in one go. Objects that were created and later dropped
-- (merchant_alias, bank_statement_templates) and the dropped bank_statement.bank_type column are
-- intentionally absent.

CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE category (
  id UUID NOT NULL,
  user_id UUID NOT NULL,
  name CITEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT category_id_pk PRIMARY KEY (id),
  CONSTRAINT category_user_id_fk FOREIGN KEY (user_id) REFERENCES users(user_id),
  CONSTRAINT category_user_id_name_unique UNIQUE (user_id, name)
);

CREATE INDEX category_user_id_index ON category USING BTREE (user_id);

CREATE TABLE merchant (
  id UUID NOT NULL,
  user_id UUID NOT NULL,
  name CITEXT NOT NULL,
  notes TEXT,
  category_id UUID,
  tax_id TEXT,
  reviewed BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT merchant_id_pk PRIMARY KEY (id),
  CONSTRAINT merchant_user_id_fk FOREIGN KEY (user_id) REFERENCES users(user_id),
  CONSTRAINT merchant_category_id_fk FOREIGN KEY (category_id) REFERENCES category(id) ON DELETE SET NULL,
  CONSTRAINT merchant_user_id_name_unique UNIQUE (user_id, name)
);

CREATE INDEX merchant_user_id_index ON merchant USING BTREE (user_id);
CREATE INDEX merchant_category_id_index ON merchant USING BTREE (category_id);
CREATE UNIQUE INDEX merchant_tax_id_user_idx ON merchant (user_id, tax_id) WHERE tax_id IS NOT NULL;

CREATE TABLE merchant_rule (
  id UUID NOT NULL,
  user_id UUID NOT NULL,
  pattern CITEXT NOT NULL,
  action TEXT NOT NULL,
  merchant_id UUID,
  auto_created BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT merchant_rule_id_pk PRIMARY KEY (id),
  CONSTRAINT merchant_rule_user_id_fk FOREIGN KEY (user_id) REFERENCES users(user_id),
  CONSTRAINT merchant_rule_merchant_id_fk FOREIGN KEY (merchant_id) REFERENCES merchant(id) ON DELETE CASCADE
);

CREATE INDEX merchant_rule_user_id_index ON merchant_rule USING BTREE (user_id);
CREATE INDEX merchant_rule_merchant_id_index ON merchant_rule USING BTREE (merchant_id);
CREATE UNIQUE INDEX merchant_rule_ignore_unique ON merchant_rule (user_id, pattern) WHERE action = 'ignore';
CREATE UNIQUE INDEX merchant_rule_set_merchant_unique ON merchant_rule (user_id, pattern, merchant_id) WHERE action = 'set_merchant';

CREATE TABLE expense (
  id UUID NOT NULL,
  user_id UUID NOT NULL,
  amount NUMERIC(12,2) NOT NULL,
  date DATE NOT NULL,
  time TIME,
  merchant_id UUID,
  notes TEXT,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT expense_id_pk PRIMARY KEY (id),
  CONSTRAINT expense_user_id_fk FOREIGN KEY (user_id) REFERENCES users(user_id),
  CONSTRAINT expense_merchant_id_fk FOREIGN KEY (merchant_id) REFERENCES merchant(id) ON DELETE SET NULL
);

CREATE INDEX expense_user_id_index ON expense USING BTREE (user_id);
CREATE INDEX expense_merchant_id_index ON expense USING BTREE (merchant_id);

CREATE TABLE expense_category_split (
  id UUID NOT NULL,
  user_id UUID NOT NULL,
  expense_id UUID NOT NULL,
  category_id UUID NOT NULL,
  amount NUMERIC(12,2) NOT NULL,
  CONSTRAINT expense_category_split_id_pk PRIMARY KEY (id),
  CONSTRAINT expense_category_split_user_id_fk FOREIGN KEY (user_id) REFERENCES users(user_id),
  CONSTRAINT expense_category_split_expense_id_fk FOREIGN KEY (expense_id) REFERENCES expense(id) ON DELETE CASCADE,
  CONSTRAINT expense_category_split_category_id_fk FOREIGN KEY (category_id) REFERENCES category(id) ON DELETE CASCADE,
  CONSTRAINT expense_category_split_expense_category_unique UNIQUE (expense_id, category_id)
);

CREATE INDEX expense_category_split_expense_id_index ON expense_category_split USING BTREE (expense_id);
CREATE INDEX expense_category_split_category_id_index ON expense_category_split USING BTREE (category_id);
CREATE INDEX expense_category_split_user_id_index ON expense_category_split USING BTREE (user_id);

CREATE TABLE refund (
  id UUID NOT NULL,
  user_id UUID NOT NULL,
  amount NUMERIC(12,2) NOT NULL,
  date DATE NOT NULL,
  merchant_id UUID,
  category_id UUID,
  expense_id UUID,
  notes TEXT,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT refund_id_pk PRIMARY KEY (id),
  CONSTRAINT refund_user_id_fk FOREIGN KEY (user_id) REFERENCES users(user_id),
  CONSTRAINT refund_merchant_id_fk FOREIGN KEY (merchant_id) REFERENCES merchant(id) ON DELETE SET NULL,
  CONSTRAINT refund_category_id_fk FOREIGN KEY (category_id) REFERENCES category(id) ON DELETE SET NULL,
  CONSTRAINT refund_expense_id_fk FOREIGN KEY (expense_id) REFERENCES expense(id) ON DELETE SET NULL
);

CREATE INDEX refund_user_id_index ON refund USING BTREE (user_id);
CREATE INDEX refund_merchant_id_index ON refund USING BTREE (merchant_id);
CREATE INDEX refund_category_id_index ON refund USING BTREE (category_id);
CREATE INDEX refund_expense_id_index ON refund USING BTREE (expense_id);

CREATE TABLE document (
  id UUID NOT NULL,
  user_id UUID NOT NULL,
  sha256 TEXT NOT NULL,
  storage_key TEXT NOT NULL,
  kind TEXT NOT NULL,
  filename TEXT NOT NULL,
  status TEXT NOT NULL,
  parser_version TEXT,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT document_id_pk PRIMARY KEY (id),
  CONSTRAINT document_user_id_fk FOREIGN KEY (user_id) REFERENCES users(user_id),
  CONSTRAINT document_user_sha256_unique UNIQUE (user_id, sha256)
);

CREATE INDEX document_user_id_index ON document USING BTREE (user_id);

CREATE TABLE textract_cache (
  id UUID NOT NULL DEFAULT gen_random_uuid(),
  sha256 TEXT NOT NULL,
  api_type TEXT NOT NULL,
  response JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT textract_cache_id_pk PRIMARY KEY (id),
  CONSTRAINT textract_cache_sha256_api_type_key UNIQUE (sha256, api_type)
);

CREATE TABLE textract_job (
  id UUID NOT NULL,
  user_id UUID NOT NULL,
  document_id UUID NOT NULL,
  api_type TEXT NOT NULL,
  job_id TEXT,
  status TEXT NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  last_polled_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT textract_job_id_pk PRIMARY KEY (id),
  CONSTRAINT textract_job_user_id_fk FOREIGN KEY (user_id) REFERENCES users(user_id),
  CONSTRAINT textract_job_document_id_fk FOREIGN KEY (document_id) REFERENCES document(id) ON DELETE CASCADE
);

CREATE INDEX textract_job_document_id_index ON textract_job USING BTREE (document_id);
CREATE INDEX textract_job_status_index ON textract_job USING BTREE (status);

CREATE TABLE bank_statement (
  id UUID NOT NULL,
  document_id UUID NOT NULL,
  user_id UUID NOT NULL,
  bank_name TEXT NOT NULL,
  product_type TEXT NOT NULL,
  currency TEXT NOT NULL,
  account_last4 TEXT NOT NULL,
  period_start DATE NOT NULL,
  period_end DATE NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT bank_statement_id_pk PRIMARY KEY (id),
  CONSTRAINT bank_statement_user_id_fk FOREIGN KEY (user_id) REFERENCES users(user_id),
  CONSTRAINT bank_statement_document_id_fk FOREIGN KEY (document_id) REFERENCES document(id) ON DELETE CASCADE,
  CONSTRAINT bank_statement_document_id_uq UNIQUE (document_id)
);

CREATE INDEX bank_statement_user_id_index ON bank_statement USING BTREE (user_id);

CREATE TABLE bank_statement_row (
  id UUID NOT NULL,
  bank_statement_id UUID NOT NULL,
  index INTEGER NOT NULL,
  date DATE NOT NULL,
  amount NUMERIC(12,2) NOT NULL,
  description TEXT NOT NULL,
  txn_type TEXT NOT NULL,
  manually_edited BOOLEAN NOT NULL DEFAULT FALSE,
  confirmed_at TIMESTAMPTZ,
  expense_id UUID,
  refund_id UUID,
  ignored_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT bank_statement_row_id_pk PRIMARY KEY (id),
  CONSTRAINT bank_statement_row_bank_statement_id_fk FOREIGN KEY (bank_statement_id) REFERENCES bank_statement(id) ON DELETE CASCADE,
  CONSTRAINT bank_statement_row_expense_id_fk FOREIGN KEY (expense_id) REFERENCES expense(id) ON DELETE SET NULL,
  CONSTRAINT bank_statement_row_refund_id_fk FOREIGN KEY (refund_id) REFERENCES refund(id) ON DELETE SET NULL
);

CREATE INDEX bank_statement_row_bank_statement_id_index ON bank_statement_row USING BTREE (bank_statement_id);
CREATE INDEX bank_statement_row_refund_id_index ON bank_statement_row USING BTREE (refund_id);

CREATE TABLE receipt (
  id UUID NOT NULL,
  user_id UUID NOT NULL,
  document_id UUID NOT NULL,
  amount NUMERIC(12,2),
  date DATE,
  time TIME,
  merchant_name TEXT,
  tax_id TEXT,
  merchant_id UUID,
  expense_id UUID,
  refund_id UUID,
  ignored_at TIMESTAMPTZ,
  manually_edited BOOLEAN NOT NULL DEFAULT FALSE,
  parser_version TEXT,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT receipt_id_pk PRIMARY KEY (id),
  CONSTRAINT receipt_user_id_fk FOREIGN KEY (user_id) REFERENCES users(user_id),
  CONSTRAINT receipt_document_id_fk FOREIGN KEY (document_id) REFERENCES document(id) ON DELETE CASCADE,
  CONSTRAINT receipt_expense_id_fk FOREIGN KEY (expense_id) REFERENCES expense(id) ON DELETE SET NULL,
  CONSTRAINT receipt_refund_id_fk FOREIGN KEY (refund_id) REFERENCES refund(id) ON DELETE SET NULL,
  CONSTRAINT receipt_merchant_id_fk FOREIGN KEY (merchant_id) REFERENCES merchant(id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX receipt_document_id_uq ON receipt (document_id);
CREATE INDEX receipt_user_id_index ON receipt USING BTREE (user_id);
CREATE INDEX receipt_refund_id_index ON receipt USING BTREE (refund_id);

CREATE TABLE savings_goal (
  id UUID NOT NULL,
  user_id UUID NOT NULL,
  name CITEXT NOT NULL,
  target_amount NUMERIC(12,2),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT savings_goal_id_pk PRIMARY KEY (id),
  CONSTRAINT savings_goal_user_id_fk FOREIGN KEY (user_id) REFERENCES users(user_id),
  CONSTRAINT savings_goal_user_id_name_unique UNIQUE (user_id, name)
);

CREATE INDEX savings_goal_user_id_index ON savings_goal USING BTREE (user_id);

CREATE TABLE savings_allocation (
  id UUID NOT NULL,
  user_id UUID NOT NULL,
  goal_id UUID NOT NULL,
  amount NUMERIC(12,2) NOT NULL,
  date DATE NOT NULL,
  note TEXT,
  merchant_id UUID,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT savings_allocation_id_pk PRIMARY KEY (id),
  CONSTRAINT savings_allocation_user_id_fk FOREIGN KEY (user_id) REFERENCES users(user_id),
  CONSTRAINT savings_allocation_goal_id_fk FOREIGN KEY (goal_id) REFERENCES savings_goal(id) ON DELETE CASCADE,
  CONSTRAINT savings_allocation_merchant_id_fk FOREIGN KEY (merchant_id) REFERENCES merchant(id) ON DELETE SET NULL
);

CREATE INDEX savings_allocation_user_id_index ON savings_allocation USING BTREE (user_id);
CREATE INDEX savings_allocation_goal_id_index ON savings_allocation USING BTREE (goal_id);

CREATE TABLE telegram_link_token (
  telegram_link_token_id UUID NOT NULL,
  token TEXT NOT NULL,
  user_id UUID NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT telegram_link_token_telegram_link_token_id_pk PRIMARY KEY (telegram_link_token_id),
  CONSTRAINT telegram_link_token_token_unique UNIQUE (token),
  CONSTRAINT telegram_link_token_users_fk FOREIGN KEY (user_id) REFERENCES users(user_id)
);

CREATE INDEX telegram_link_token_user_id_index ON telegram_link_token USING BTREE (user_id);

CREATE TABLE telegram_bot_user (
  telegram_bot_user_id UUID NOT NULL,
  user_id UUID NOT NULL,
  telegram_user_id BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT telegram_bot_user_telegram_bot_user_id_pk PRIMARY KEY (telegram_bot_user_id),
  CONSTRAINT telegram_bot_user_users_fk FOREIGN KEY (user_id) REFERENCES users(user_id),
  CONSTRAINT telegram_bot_user_telegram_user_id_unique UNIQUE (telegram_user_id)
);

CREATE INDEX telegram_bot_user_user_id_index ON telegram_bot_user USING BTREE (user_id);

CREATE TABLE telegram_receipt (
  telegram_receipt_id UUID NOT NULL,
  receipt_id UUID NOT NULL,
  chat_id BIGINT NOT NULL,
  expense_message_id BIGINT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT telegram_receipt_telegram_receipt_id_pk PRIMARY KEY (telegram_receipt_id),
  CONSTRAINT telegram_receipt_receipt_id_unique UNIQUE (receipt_id),
  CONSTRAINT telegram_receipt_receipt_id_fk FOREIGN KEY (receipt_id) REFERENCES receipt(id) ON DELETE CASCADE
);

CREATE TABLE mcp_api_token (
  id UUID NOT NULL,
  user_id UUID NOT NULL,
  token_hash TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  last_used_at TIMESTAMPTZ NULL,
  CONSTRAINT mcp_api_token_id_pk PRIMARY KEY (id),
  CONSTRAINT mcp_api_token_token_hash_unique UNIQUE (token_hash),
  CONSTRAINT mcp_api_token_users_fk FOREIGN KEY (user_id) REFERENCES users(user_id)
);

-- doubles as the FK's required index and the "one token per user" uniqueness constraint
CREATE UNIQUE INDEX mcp_api_token_user_id_unique ON mcp_api_token USING BTREE (user_id);

-- A per-user queue of bank-statement import drafts. The AI extraction runs in a background job, so a
-- draft exists (status='processing') before its payload is ready.
CREATE TABLE bank_statement_draft (
  id UUID NOT NULL,
  user_id UUID NOT NULL,
  sha256 TEXT NOT NULL,
  filename TEXT NOT NULL,
  storage_key TEXT NOT NULL,
  payload JSONB,
  status TEXT NOT NULL DEFAULT 'processing',
  error TEXT,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT bank_statement_draft_id_pk PRIMARY KEY (id),
  CONSTRAINT bank_statement_draft_user_id_fk FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
);
