-- !Ups

-- A pricing term of a subscription over the half-open date range [effective_from, effective_to): effective_to is
-- exclusive and NULL means the term is still open. amount_min and amount_max bound the expected charge while the
-- term applies: both NULL means the amount is unknown, equal values mean a fixed amount, and different values
-- describe a variable charge range. The term rules (no overlaps, only the last term open) are enforced by the app.
CREATE TABLE subscription_term (
  id UUID NOT NULL,
  user_id UUID NOT NULL,
  subscription_id UUID NOT NULL,
  effective_from DATE NOT NULL,
  effective_to DATE NULL,
  amount_min NUMERIC(12,2) NULL,
  amount_max NUMERIC(12,2) NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT subscription_term_id_pk PRIMARY KEY (id),
  CONSTRAINT subscription_term_user_id_fk FOREIGN KEY (user_id) REFERENCES users(user_id),
  CONSTRAINT subscription_term_subscription_id_fk FOREIGN KEY (subscription_id) REFERENCES subscription(id) ON DELETE CASCADE
);

CREATE INDEX subscription_term_user_id_index ON subscription_term USING BTREE (user_id);
CREATE INDEX subscription_term_subscription_id_index ON subscription_term USING BTREE (subscription_id);

-- Every existing subscription becomes one open term carrying its stored start date and amount range.
INSERT INTO subscription_term (
  id, user_id, subscription_id, effective_from, effective_to, amount_min, amount_max, created_at, updated_at
)
SELECT gen_random_uuid(), user_id, id, start_date, NULL, amount_min, amount_max, created_at, updated_at
FROM subscription;

ALTER TABLE subscription
  DROP COLUMN amount_min,
  DROP COLUMN amount_max,
  DROP COLUMN start_date;

-- The interval of an every-N-months cadence, set only when cadence is EveryNMonths (like interval_days for EveryNDays).
ALTER TABLE subscription ADD COLUMN interval_months INT NULL;
ALTER TABLE subscription ADD COLUMN description TEXT NULL;

-- The merchant that charges this subscription, unlinked when the merchant is deleted.
ALTER TABLE subscription ADD COLUMN merchant_id UUID NULL;
ALTER TABLE subscription
  ADD CONSTRAINT subscription_merchant_id_fk FOREIGN KEY (merchant_id) REFERENCES merchant(id) ON DELETE SET NULL;
CREATE INDEX subscription_merchant_id_index ON subscription USING BTREE (merchant_id);

-- A fee charged on top of a subscription's terms, like a yearly membership fee. due_date is the date of a one-off
-- charge, or the first occurrence of a periodic one.
CREATE TABLE subscription_extra_charge (
  id UUID NOT NULL,
  user_id UUID NOT NULL,
  subscription_id UUID NOT NULL,
  label TEXT NOT NULL,
  amount NUMERIC(12,2) NOT NULL,
  recurrence TEXT NOT NULL,
  due_date DATE NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT subscription_extra_charge_id_pk PRIMARY KEY (id),
  CONSTRAINT subscription_extra_charge_user_id_fk FOREIGN KEY (user_id) REFERENCES users(user_id),
  CONSTRAINT subscription_extra_charge_subscription_id_fk FOREIGN KEY (subscription_id) REFERENCES subscription(id) ON DELETE CASCADE
);

CREATE INDEX subscription_extra_charge_user_id_index ON subscription_extra_charge USING BTREE (user_id);
CREATE INDEX subscription_extra_charge_subscription_id_index ON subscription_extra_charge USING BTREE (subscription_id);
