-- !Ups

-- A payment the user recorded by hand for a subscription, for charges that never show up as an expense or a bank
-- statement row (like a cash payment). Reconciliation counts it as the payment of the subscription cycle around
-- paid_date. note is an optional free-text remark.
CREATE TABLE subscription_manual_payment (
  id UUID NOT NULL,
  user_id UUID NOT NULL,
  subscription_id UUID NOT NULL,
  paid_date DATE NOT NULL,
  amount NUMERIC(12,2) NOT NULL,
  note TEXT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT subscription_manual_payment_id_pk PRIMARY KEY (id),
  CONSTRAINT subscription_manual_payment_user_id_fk FOREIGN KEY (user_id) REFERENCES users(user_id),
  CONSTRAINT subscription_manual_payment_subscription_id_fk FOREIGN KEY (subscription_id) REFERENCES subscription(id) ON DELETE CASCADE
);

CREATE INDEX subscription_manual_payment_user_id_index ON subscription_manual_payment USING BTREE (user_id);
CREATE INDEX subscription_manual_payment_subscription_id_index ON subscription_manual_payment USING BTREE (subscription_id);
