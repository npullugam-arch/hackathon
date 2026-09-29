-- Recharge principal and the Razorpay ledger are deliberately unchanged.
ALTER TABLE public.users ADD COLUMN winning_balance_paise BIGINT NOT NULL DEFAULT 0 CHECK (winning_balance_paise >= 0);

CREATE TABLE app_private.bank_accounts (
    id UUID PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES public.users(firebase_uid),
    bank_code VARCHAR(4) NOT NULL,
    bank_name VARCHAR(200) NOT NULL,
    holder_name VARCHAR(160) NOT NULL,
    account_ciphertext TEXT NOT NULL,
    account_fingerprint VARCHAR(64) NOT NULL,
    account_last_four VARCHAR(4) NOT NULL CHECK (account_last_four ~ '^[0-9]{4}$'),
    ifsc VARCHAR(11) NOT NULL CHECK (ifsc ~ '^[A-Z]{4}0[A-Z0-9]{6}$'),
    nickname VARCHAR(60) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, account_fingerprint),
    UNIQUE (id, user_id)
);
CREATE INDEX bank_accounts_user_idx ON app_private.bank_accounts(user_id, active);

CREATE TABLE app_private.withdrawals (
    id UUID PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES public.users(firebase_uid),
    bank_account_id UUID NOT NULL,
    amount_paise BIGINT NOT NULL CHECK (amount_paise >= 10000),
    idempotency_key UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PROCESSING' CHECK (status IN ('PROCESSING','COMPLETED','REFUNDED')),
    failure_kind VARCHAR(16) CHECK (failure_kind IN ('FAILED','REJECTED')),
    failure_reason VARCHAR(500),
    admin_remark VARCHAR(500),
    reference_id VARCHAR(120),
    processed_by VARCHAR(254),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    rejected_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (bank_account_id, user_id) REFERENCES app_private.bank_accounts(id, user_id),
    UNIQUE (user_id, idempotency_key),
    UNIQUE (id, user_id),
    CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)),
    CHECK (status <> 'COMPLETED' OR (reference_id IS NOT NULL AND length(trim(reference_id)) > 0)),
    CHECK ((status = 'REFUNDED') = (failure_kind IS NOT NULL AND failure_reason IS NOT NULL))
);
CREATE UNIQUE INDEX withdrawals_reference_idx ON app_private.withdrawals(lower(reference_id)) WHERE status='COMPLETED';
CREATE INDEX withdrawals_user_idx ON app_private.withdrawals(user_id, requested_at DESC, id DESC);
CREATE INDEX withdrawals_status_idx ON app_private.withdrawals(status, requested_at DESC, id DESC);
CREATE INDEX withdrawals_requested_idx ON app_private.withdrawals(requested_at DESC, id DESC);

-- A distinct ledger is necessary because the existing ledger accepts only captured Razorpay recharges.
-- The balance is a projection of these entries on the existing user; there is no second user wallet.
CREATE TABLE app_private.winning_transactions (
    id UUID PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES public.users(firebase_uid),
    withdrawal_id UUID,
    source_id VARCHAR(160),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('EARNING','RESERVE','REFUND','COMPLETE')),
    amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
    actor VARCHAR(254) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (withdrawal_id, user_id) REFERENCES app_private.withdrawals(id, user_id),
    UNIQUE (withdrawal_id, kind),
    UNIQUE (user_id, source_id),
    CHECK ((kind = 'EARNING' AND withdrawal_id IS NULL AND source_id IS NOT NULL) OR
           (kind <> 'EARNING' AND withdrawal_id IS NOT NULL AND source_id IS NULL))
);
CREATE INDEX winning_transactions_user_idx ON app_private.winning_transactions(user_id, created_at DESC);
CREATE FUNCTION app_private.apply_winning_entry() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE expected_amount BIGINT; expected_status TEXT;
BEGIN
    IF NEW.kind <> 'EARNING' THEN
        SELECT amount_paise, status INTO expected_amount, expected_status FROM app_private.withdrawals
            WHERE id=NEW.withdrawal_id AND user_id=NEW.user_id FOR UPDATE;
        IF expected_amount IS DISTINCT FROM NEW.amount_paise OR
           (NEW.kind='RESERVE' AND expected_status<>'PROCESSING') OR
           (NEW.kind='REFUND' AND expected_status<>'REFUNDED') OR
           (NEW.kind='COMPLETE' AND expected_status<>'COMPLETED') THEN
            RAISE EXCEPTION 'Invalid withdrawal ledger transition';
        END IF;
        IF NEW.kind IN ('REFUND','COMPLETE') AND NOT EXISTS
           (SELECT 1 FROM app_private.winning_transactions WHERE withdrawal_id=NEW.withdrawal_id AND kind='RESERVE') THEN
            RAISE EXCEPTION 'Withdrawal reservation missing';
        END IF;
    END IF;
    IF NEW.kind <> 'COMPLETE' THEN
        UPDATE public.users SET winning_balance_paise=winning_balance_paise+
            CASE WHEN NEW.kind='RESERVE' THEN -NEW.amount_paise ELSE NEW.amount_paise END,
            updated_at=CURRENT_TIMESTAMP WHERE firebase_uid=NEW.user_id;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER apply_winning_entry AFTER INSERT ON app_private.winning_transactions
    FOR EACH ROW EXECUTE FUNCTION app_private.apply_winning_entry();

CREATE TABLE app_private.withdrawal_audit (
    id UUID PRIMARY KEY,
    withdrawal_id UUID NOT NULL REFERENCES app_private.withdrawals(id),
    actor VARCHAR(254) NOT NULL,
    action VARCHAR(32) NOT NULL,
    remark VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX withdrawal_audit_request_idx ON app_private.withdrawal_audit(withdrawal_id, created_at);
CREATE FUNCTION app_private.reject_ledger_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Financial ledger and audit entries are immutable'; END $$;
CREATE TRIGGER winning_ledger_immutable BEFORE UPDATE OR DELETE ON app_private.winning_transactions
    FOR EACH ROW EXECUTE FUNCTION app_private.reject_ledger_mutation();
CREATE TRIGGER withdrawal_audit_immutable BEFORE UPDATE OR DELETE ON app_private.withdrawal_audit
    FOR EACH ROW EXECUTE FUNCTION app_private.reject_ledger_mutation();

ALTER TABLE app_private.bank_accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_private.withdrawals ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_private.winning_transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_private.withdrawal_audit ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON app_private.bank_accounts, app_private.withdrawals, app_private.winning_transactions, app_private.withdrawal_audit FROM PUBLIC;
REVOKE ALL ON FUNCTION app_private.apply_winning_entry(), app_private.reject_ledger_mutation() FROM PUBLIC;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='anon') THEN
        REVOKE ALL ON app_private.bank_accounts, app_private.withdrawals, app_private.winning_transactions, app_private.withdrawal_audit FROM anon;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='authenticated') THEN
        REVOKE ALL ON app_private.bank_accounts, app_private.withdrawals, app_private.winning_transactions, app_private.withdrawal_audit FROM authenticated;
    END IF;
END $$;
