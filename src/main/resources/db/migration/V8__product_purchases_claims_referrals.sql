-- Every existing and future Firebase user receives a permanent invitation code.
CREATE TABLE app_private.product_images (
    id UUID PRIMARY KEY,
    image_data BYTEA NOT NULL CHECK(octet_length(image_data)<=8388608),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE app_private.product_images ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON app_private.product_images FROM PUBLIC;
ALTER TABLE public.users ADD COLUMN referral_code VARCHAR(32) NOT NULL
    DEFAULT upper(replace(gen_random_uuid()::text,'-',''));
ALTER TABLE public.users ADD CONSTRAINT users_referral_code_unique UNIQUE(referral_code);
ALTER TABLE public.users ADD CONSTRAINT users_referral_code_format CHECK(referral_code ~ '^[A-F0-9]{32}$');

CREATE TABLE app_private.product_purchases (
    id UUID PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES public.users(firebase_uid),
    product_id UUID NOT NULL REFERENCES public.products(id),
    product_title VARCHAR(160) NOT NULL,
    image_url TEXT NOT NULL,
    purchase_amount_paise BIGINT NOT NULL CHECK(purchase_amount_paise >= 100),
    daily_income_paise BIGINT NOT NULL CHECK(daily_income_paise >= 0),
    duration_days INTEGER NOT NULL CHECK(duration_days BETWEEN 1 AND 3650),
    configured_start_at TIMESTAMPTZ NOT NULL,
    claim_zone VARCHAR(64) NOT NULL,
    claim_time TIME NOT NULL,
    razorpay_order_id VARCHAR(80) UNIQUE,
    razorpay_payment_id VARCHAR(80) UNIQUE,
    payment_status VARCHAR(16) NOT NULL DEFAULT 'CREATING' CHECK(payment_status IN ('CREATING','PENDING','FAILED','PAID')),
    paid_at TIMESTAMPTZ,
    first_claim_at TIMESTAMPTZ,
    end_at TIMESTAMPTZ,
    last_checked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id,product_id),
    UNIQUE(id,user_id),
    CHECK ((payment_status='PAID')=(paid_at IS NOT NULL)),
    CHECK (payment_status<>'PAID' OR (razorpay_payment_id IS NOT NULL AND first_claim_at IS NOT NULL AND end_at>first_claim_at))
);
CREATE INDEX product_purchases_user_idx ON app_private.product_purchases(user_id,created_at DESC,id DESC);
CREATE INDEX product_purchases_product_idx ON app_private.product_purchases(product_id,payment_status);
CREATE INDEX product_purchases_payment_idx ON app_private.product_purchases(payment_status,created_at DESC);
CREATE INDEX product_purchases_lifecycle_idx ON app_private.product_purchases(end_at) WHERE payment_status='PAID';

-- Purchase receipts record external payments; principal is never a winning-ledger credit.
CREATE TABLE app_private.product_payment_transactions (
    id UUID PRIMARY KEY,
    purchase_id UUID NOT NULL UNIQUE REFERENCES app_private.product_purchases(id),
    razorpay_payment_id VARCHAR(80) NOT NULL UNIQUE,
    amount_paise BIGINT NOT NULL CHECK(amount_paise>0),
    currency VARCHAR(3) NOT NULL DEFAULT 'INR' CHECK(currency='INR'),
    kind VARCHAR(16) NOT NULL DEFAULT 'PURCHASE' CHECK(kind='PURCHASE'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE app_private.product_daily_claims (
    id UUID PRIMARY KEY,
    purchase_id UUID NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    day_number INTEGER NOT NULL CHECK(day_number BETWEEN 1 AND 3650),
    eligible_at TIMESTAMPTZ NOT NULL,
    amount_paise BIGINT NOT NULL CHECK(amount_paise>0),
    ledger_id UUID NOT NULL UNIQUE REFERENCES app_private.winning_transactions(id),
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(purchase_id,user_id) REFERENCES app_private.product_purchases(id,user_id),
    UNIQUE(purchase_id,day_number)
);
CREATE INDEX product_daily_claims_user_idx ON app_private.product_daily_claims(user_id,claimed_at DESC);

CREATE TABLE app_private.referrals (
    id UUID PRIMARY KEY,
    inviter_id VARCHAR(128) NOT NULL REFERENCES public.users(firebase_uid),
    invitee_id VARCHAR(128) NOT NULL UNIQUE REFERENCES public.users(firebase_uid),
    referral_code VARCHAR(32) NOT NULL REFERENCES public.users(referral_code),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK(inviter_id<>invitee_id)
);
CREATE INDEX referrals_inviter_idx ON app_private.referrals(inviter_id,created_at DESC);
CREATE TABLE app_private.referral_rewards (
    id UUID PRIMARY KEY,
    referral_id UUID NOT NULL REFERENCES app_private.referrals(id),
    purchase_id UUID NOT NULL REFERENCES app_private.product_purchases(id),
    beneficiary_id VARCHAR(128) NOT NULL REFERENCES public.users(firebase_uid),
    role VARCHAR(8) NOT NULL CHECK(role IN ('INVITER','INVITEE')),
    amount_paise BIGINT NOT NULL DEFAULT 5000 CHECK(amount_paise=5000),
    ledger_id UUID NOT NULL UNIQUE REFERENCES app_private.winning_transactions(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(referral_id,role),
    UNIQUE(referral_id,beneficiary_id)
);
CREATE INDEX referral_rewards_user_idx ON app_private.referral_rewards(beneficiary_id);

CREATE TRIGGER purchase_payment_immutable BEFORE UPDATE OR DELETE ON app_private.product_payment_transactions
    FOR EACH ROW EXECUTE FUNCTION app_private.reject_ledger_mutation();
CREATE TRIGGER daily_claim_immutable BEFORE UPDATE OR DELETE ON app_private.product_daily_claims
    FOR EACH ROW EXECUTE FUNCTION app_private.reject_ledger_mutation();
CREATE TRIGGER referral_binding_immutable BEFORE UPDATE OR DELETE ON app_private.referrals
    FOR EACH ROW EXECUTE FUNCTION app_private.reject_ledger_mutation();
CREATE TRIGGER referral_reward_immutable BEFORE UPDATE OR DELETE ON app_private.referral_rewards
    FOR EACH ROW EXECUTE FUNCTION app_private.reject_ledger_mutation();

-- Existing sold terms are immutable, even if the product configuration is changed later.
CREATE FUNCTION app_private.protect_purchase_terms() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.user_id,NEW.product_id,NEW.product_title,NEW.image_url,NEW.purchase_amount_paise,NEW.daily_income_paise,
        NEW.duration_days,NEW.configured_start_at,NEW.claim_zone,NEW.claim_time) IS DISTINCT FROM
       (OLD.user_id,OLD.product_id,OLD.product_title,OLD.image_url,OLD.purchase_amount_paise,OLD.daily_income_paise,
        OLD.duration_days,OLD.configured_start_at,OLD.claim_zone,OLD.claim_time) THEN
        RAISE EXCEPTION 'Purchase terms cannot be changed';
    END IF;
    IF OLD.razorpay_order_id IS NOT NULL AND NEW.razorpay_order_id IS DISTINCT FROM OLD.razorpay_order_id THEN
        RAISE EXCEPTION 'Purchase provider order cannot be replaced';
    END IF;
    IF OLD.payment_status='PAID' AND
       (NEW.payment_status,NEW.razorpay_payment_id,NEW.paid_at,NEW.first_claim_at,NEW.end_at) IS DISTINCT FROM
       (OLD.payment_status,OLD.razorpay_payment_id,OLD.paid_at,OLD.first_claim_at,OLD.end_at) THEN
        RAISE EXCEPTION 'Paid purchase cannot be changed';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER protect_purchase_terms BEFORE UPDATE ON app_private.product_purchases
    FOR EACH ROW EXECUTE FUNCTION app_private.protect_purchase_terms();

ALTER TABLE app_private.product_purchases ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_private.product_payment_transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_private.product_daily_claims ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_private.referrals ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_private.referral_rewards ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON app_private.product_purchases,app_private.product_payment_transactions,app_private.product_daily_claims,app_private.referrals,app_private.referral_rewards FROM PUBLIC;
REVOKE ALL ON FUNCTION app_private.protect_purchase_terms() FROM PUBLIC;
DO $$ BEGIN
    IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='anon') THEN
        REVOKE ALL ON app_private.product_purchases,app_private.product_payment_transactions,app_private.product_daily_claims,app_private.referrals,app_private.referral_rewards,app_private.product_images FROM anon;
    END IF;
    IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='authenticated') THEN
        REVOKE ALL ON app_private.product_purchases,app_private.product_payment_transactions,app_private.product_daily_claims,app_private.referrals,app_private.referral_rewards,app_private.product_images FROM authenticated;
    END IF;
END $$;
