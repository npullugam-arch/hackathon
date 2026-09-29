-- Preserve existing fixed-rate receipts and schedules. New purchases snapshot the range.
ALTER TABLE public.products ADD COLUMN minimum_daily_income NUMERIC(14,2);
UPDATE public.products SET minimum_daily_income=daily_income;
ALTER TABLE public.products ALTER COLUMN minimum_daily_income SET NOT NULL;
ALTER TABLE public.products ADD CONSTRAINT product_income_range CHECK(minimum_daily_income>=0 AND daily_income>=minimum_daily_income);
ALTER TABLE app_private.product_purchases ADD COLUMN minimum_daily_income_paise BIGINT;
UPDATE app_private.product_purchases SET minimum_daily_income_paise=daily_income_paise;
ALTER TABLE app_private.product_purchases ALTER COLUMN minimum_daily_income_paise SET NOT NULL;
ALTER TABLE app_private.product_purchases ADD CONSTRAINT purchase_income_range CHECK(minimum_daily_income_paise>=0 AND daily_income_paise>=minimum_daily_income_paise);

CREATE TABLE app_private.product_daily_amounts (
    purchase_id UUID NOT NULL REFERENCES app_private.product_purchases(id),
    day_number INTEGER NOT NULL CHECK(day_number BETWEEN 1 AND 3650),
    amount_paise BIGINT NOT NULL CHECK(amount_paise>=0),
    PRIMARY KEY(purchase_id,day_number)
);
INSERT INTO app_private.product_daily_amounts(purchase_id,day_number,amount_paise)
SELECT id,generate_series(1,duration_days),daily_income_paise FROM app_private.product_purchases;
ALTER TABLE app_private.product_daily_amounts ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON app_private.product_daily_amounts FROM PUBLIC;
DO $$ BEGIN
    IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='anon') THEN REVOKE ALL ON app_private.product_daily_amounts FROM anon; END IF;
    IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='authenticated') THEN REVOKE ALL ON app_private.product_daily_amounts FROM authenticated; END IF;
END $$;
CREATE TRIGGER daily_amount_immutable BEFORE UPDATE OR DELETE ON app_private.product_daily_amounts
    FOR EACH ROW EXECUTE FUNCTION app_private.reject_ledger_mutation();
CREATE FUNCTION app_private.validate_daily_amount() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE p app_private.product_purchases%ROWTYPE;
BEGIN
    SELECT * INTO p FROM app_private.product_purchases WHERE id=NEW.purchase_id;
    IF NEW.day_number>p.duration_days OR NEW.amount_paise<p.minimum_daily_income_paise OR NEW.amount_paise>p.daily_income_paise THEN
        RAISE EXCEPTION 'Daily amount outside purchase terms';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER daily_amount_range BEFORE INSERT ON app_private.product_daily_amounts FOR EACH ROW EXECUTE FUNCTION app_private.validate_daily_amount();
CREATE FUNCTION app_private.protect_income_range() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.minimum_daily_income_paise IS DISTINCT FROM OLD.minimum_daily_income_paise THEN RAISE EXCEPTION 'Purchase range is immutable'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER protect_income_range BEFORE UPDATE ON app_private.product_purchases FOR EACH ROW EXECUTE FUNCTION app_private.protect_income_range();
CREATE FUNCTION app_private.positive_product_income() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.minimum_daily_income<=0 OR NEW.daily_income<=0 THEN RAISE EXCEPTION 'Daily income must be positive'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER positive_product_income BEFORE INSERT OR UPDATE OF daily_income,minimum_daily_income ON public.products FOR EACH ROW EXECUTE FUNCTION app_private.positive_product_income();

CREATE OR REPLACE FUNCTION app_private.validate_product_claim() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE p app_private.product_purchases%ROWTYPE; expected_at TIMESTAMPTZ; expires_at TIMESTAMPTZ; generated BIGINT;
BEGIN
    SELECT * INTO p FROM app_private.product_purchases WHERE id=NEW.purchase_id FOR UPDATE;
    expected_at=CASE WHEN NEW.day_number=1 THEN p.first_claim_at ELSE
        ((p.first_claim_at AT TIME ZONE p.claim_zone)::date+(NEW.day_number-1)+p.claim_time) AT TIME ZONE p.claim_zone END;
    expires_at=((p.first_claim_at AT TIME ZONE p.claim_zone)::date+NEW.day_number+p.claim_time) AT TIME ZONE p.claim_zone;
    SELECT amount_paise INTO generated FROM app_private.product_daily_amounts WHERE purchase_id=p.id AND day_number=NEW.day_number;
    IF p.payment_status<>'PAID' OR p.user_id<>NEW.user_id OR NEW.day_number>p.duration_days
       OR generated IS NULL OR NEW.amount_paise<>generated
       OR NEW.amount_paise<p.minimum_daily_income_paise OR NEW.amount_paise>p.daily_income_paise OR NEW.eligible_at<>expected_at
       OR NEW.claimed_at<expected_at OR NEW.claimed_at>=expires_at OR NEW.claimed_at>=p.end_at THEN
        RAISE EXCEPTION 'Invalid product claim';
    END IF;
    IF NOT EXISTS(SELECT 1 FROM app_private.winning_transactions w WHERE w.id=NEW.ledger_id AND w.user_id=NEW.user_id
        AND w.kind='EARNING' AND w.amount_paise=NEW.amount_paise AND w.source_id='product-claim:'||NEW.purchase_id||':'||NEW.day_number) THEN
        RAISE EXCEPTION 'Product claim ledger mismatch';
    END IF;
    RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION app_private.validate_daily_amount(),app_private.protect_income_range(),app_private.positive_product_income() FROM PUBLIC;
