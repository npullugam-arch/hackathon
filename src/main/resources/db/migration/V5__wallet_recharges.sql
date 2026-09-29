ALTER TABLE public.users ADD COLUMN wallet_balance_paise BIGINT NOT NULL DEFAULT 0
    CHECK (wallet_balance_paise >= 0);

CREATE TABLE app_private.recharge_orders (
    id UUID PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES public.users(firebase_uid),
    razorpay_order_id VARCHAR(80) NOT NULL UNIQUE,
    amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'INR'),
    status VARCHAR(16) NOT NULL DEFAULT 'CREATED' CHECK (status IN ('CREATED', 'CREDITED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX recharge_orders_user_idx ON app_private.recharge_orders(user_id, created_at DESC);

CREATE TABLE app_private.wallet_transactions (
    id UUID PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES public.users(firebase_uid),
    razorpay_order_id VARCHAR(80) NOT NULL UNIQUE REFERENCES app_private.recharge_orders(razorpay_order_id),
    razorpay_payment_id VARCHAR(80) NOT NULL UNIQUE,
    amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'INR'),
    payment_status VARCHAR(16) NOT NULL CHECK (payment_status = 'CAPTURED'),
    transaction_type VARCHAR(16) NOT NULL CHECK (transaction_type = 'RECHARGE'),
    direction VARCHAR(8) NOT NULL CHECK (direction = 'CREDIT'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX wallet_transactions_user_idx ON app_private.wallet_transactions(user_id, created_at DESC);
ALTER TABLE app_private.recharge_orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_private.wallet_transactions ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON app_private.recharge_orders, app_private.wallet_transactions FROM PUBLIC;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
        REVOKE ALL ON app_private.recharge_orders, app_private.wallet_transactions FROM anon;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
        REVOKE ALL ON app_private.recharge_orders, app_private.wallet_transactions FROM authenticated;
    END IF;
END $$;
