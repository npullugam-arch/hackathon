-- Keep identity data on the existing user, and observations on the existing recharge order.
ALTER TABLE public.users ADD COLUMN phone_number VARCHAR(32);
ALTER TABLE app_private.recharge_orders DROP CONSTRAINT recharge_orders_status_check;
ALTER TABLE app_private.recharge_orders ADD CONSTRAINT recharge_orders_status_check
    CHECK (status IN ('CREATED', 'FAILED', 'CREDITED'));
ALTER TABLE app_private.recharge_orders
    ADD COLUMN last_payment_id VARCHAR(80),
    ADD COLUMN provider_status VARCHAR(24),
    ADD COLUMN payment_created_at BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN status_checked_at TIMESTAMPTZ;
CREATE INDEX recharge_orders_created_idx ON app_private.recharge_orders(created_at DESC, id DESC);
CREATE INDEX recharge_orders_status_created_idx ON app_private.recharge_orders(status, created_at DESC);
