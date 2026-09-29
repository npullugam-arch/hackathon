-- The trusted service supplies its configured minimum inside the withdrawal transaction.
-- A direct database insert retains the testing default; amounts remain immutable.
CREATE OR REPLACE FUNCTION app_private.enforce_withdrawal_minimum() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE minimum BIGINT=COALESCE(NULLIF(current_setting('app.withdrawal_minimum_paise',true),''),'1000')::bigint;
BEGIN
    IF TG_OP='INSERT' AND (minimum<=0 OR NEW.amount_paise<minimum) THEN RAISE EXCEPTION 'Withdrawal below configured minimum'; END IF;
    IF TG_OP='UPDATE' AND NEW.amount_paise<>OLD.amount_paise THEN RAISE EXCEPTION 'Withdrawal amounts are immutable'; END IF;
    RETURN NEW;
END $$;
