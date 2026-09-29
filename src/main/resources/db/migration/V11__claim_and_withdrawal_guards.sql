-- Retain historical small withdrawals from V9, but enforce the restored INR 100 minimum on every new request.
CREATE FUNCTION app_private.enforce_withdrawal_minimum() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' AND NEW.amount_paise<10000 THEN RAISE EXCEPTION 'Minimum withdrawal is INR 100'; END IF;
    IF TG_OP='UPDATE' AND NEW.amount_paise<>OLD.amount_paise THEN RAISE EXCEPTION 'Withdrawal amounts are immutable'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER withdrawal_minimum BEFORE INSERT OR UPDATE OF amount_paise ON app_private.withdrawals
    FOR EACH ROW EXECUTE FUNCTION app_private.enforce_withdrawal_minimum();
REVOKE ALL ON FUNCTION app_private.enforce_withdrawal_minimum() FROM PUBLIC;
-- V10 supports wallet-funded purchases; each purchase may debit the recharge wallet only once.
CREATE UNIQUE INDEX wallet_purchase_once_idx ON app_private.wallet_transactions(purchase_id) WHERE purchase_id IS NOT NULL;
CREATE UNIQUE INDEX purchase_provider_payment_once_idx ON app_private.product_purchases(razorpay_payment_id) WHERE razorpay_payment_id IS NOT NULL;
CREATE INDEX product_daily_claims_purchase_idx ON app_private.product_daily_claims(purchase_id,day_number);
ALTER TABLE app_private.bank_accounts ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE FUNCTION app_private.validate_product_claim() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE p app_private.product_purchases%ROWTYPE; expected_at TIMESTAMPTZ;
BEGIN
    SELECT * INTO p FROM app_private.product_purchases WHERE id=NEW.purchase_id FOR UPDATE;
    expected_at=((p.first_claim_at AT TIME ZONE p.claim_zone)+(NEW.day_number-1)*INTERVAL '1 day') AT TIME ZONE p.claim_zone;
    IF p.payment_status<>'PAID' OR p.user_id<>NEW.user_id OR NEW.day_number>p.duration_days
       OR NEW.amount_paise<>p.daily_income_paise OR NEW.eligible_at<>expected_at
       OR NEW.claimed_at<expected_at OR NEW.claimed_at>=((expected_at AT TIME ZONE p.claim_zone)+INTERVAL '1 day') AT TIME ZONE p.claim_zone
       OR NEW.claimed_at>=p.end_at THEN RAISE EXCEPTION 'Invalid product claim'; END IF;
    IF NOT EXISTS(SELECT 1 FROM app_private.winning_transactions w WHERE w.id=NEW.ledger_id AND w.user_id=NEW.user_id
        AND w.kind='EARNING' AND w.amount_paise=NEW.amount_paise AND w.source_id='product-claim:'||NEW.purchase_id||':'||NEW.day_number) THEN
        RAISE EXCEPTION 'Product claim ledger mismatch';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER product_claim_validation BEFORE INSERT ON app_private.product_daily_claims FOR EACH ROW EXECUTE FUNCTION app_private.validate_product_claim();
REVOKE ALL ON FUNCTION app_private.validate_product_claim() FROM PUBLIC;
