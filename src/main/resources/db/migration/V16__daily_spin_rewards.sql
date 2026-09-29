CREATE TABLE app_private.spin_rewards (
    id UUID PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES public.users(firebase_uid),
    request_id UUID NOT NULL,
    amount_paise BIGINT NOT NULL CHECK (amount_paise IN (100,200,500,1000,2000,3000)),
    awarded_at TIMESTAMPTZ NOT NULL,
    spin_day DATE GENERATED ALWAYS AS ((awarded_at AT TIME ZONE 'Asia/Kolkata')::DATE) STORED,
    ledger_id UUID NOT NULL UNIQUE REFERENCES app_private.winning_transactions(id),
    reward_weights VARCHAR(160) NOT NULL,
    UNIQUE (user_id, spin_day),
    UNIQUE (user_id, request_id)
);
CREATE INDEX spin_rewards_history_idx ON app_private.spin_rewards(user_id, awarded_at DESC, id DESC);
CREATE TRIGGER spin_rewards_immutable BEFORE UPDATE OR DELETE ON app_private.spin_rewards
FOR EACH ROW EXECUTE FUNCTION app_private.reject_ledger_mutation();

CREATE FUNCTION app_private.validate_spin_reward() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM app_private.winning_transactions
        WHERE id=NEW.ledger_id AND user_id=NEW.user_id AND kind='EARNING'
        AND amount_paise=NEW.amount_paise AND source_id='daily-spin:'||NEW.id) THEN
        RAISE EXCEPTION 'Spin reward ledger mismatch';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER spin_reward_ledger_check BEFORE INSERT ON app_private.spin_rewards
FOR EACH ROW EXECUTE FUNCTION app_private.validate_spin_reward();

-- A credit and its spin receipt must commit together, even if application code changes.
CREATE FUNCTION app_private.require_spin_receipt() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.kind='EARNING' AND NEW.source_id LIKE 'daily-spin:%' AND NOT EXISTS
        (SELECT 1 FROM app_private.spin_rewards WHERE ledger_id=NEW.id) THEN
        RAISE EXCEPTION 'Spin reward receipt missing';
    END IF;
    RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER spin_credit_receipt_check AFTER INSERT ON app_private.winning_transactions
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION app_private.require_spin_receipt();

ALTER TABLE app_private.spin_rewards ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON app_private.spin_rewards FROM PUBLIC;
REVOKE ALL ON FUNCTION app_private.validate_spin_reward(),app_private.require_spin_receipt() FROM PUBLIC;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='anon') THEN REVOKE ALL ON app_private.spin_rewards FROM anon; END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='authenticated') THEN REVOKE ALL ON app_private.spin_rewards FROM authenticated; END IF;
END $$;
