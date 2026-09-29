-- Upgrade existing success records and all associated checks atomically.
DO $$
DECLARE c RECORD; names TEXT[] := '{}'; definitions TEXT[] := '{}'; i INTEGER;
BEGIN
 FOR c IN SELECT conname,pg_get_constraintdef(oid) AS definition FROM pg_constraint
 WHERE conrelid='app_private.withdrawals'::regclass AND contype='c'
 AND pg_get_constraintdef(oid) LIKE '%COMPLETED%' LOOP
  names:=array_append(names,c.conname); definitions:=array_append(definitions,replace(c.definition,'COMPLETED','SUCCESSFUL'));
  EXECUTE format('ALTER TABLE app_private.withdrawals DROP CONSTRAINT %I',c.conname);
 END LOOP;
 UPDATE app_private.withdrawals SET status='SUCCESSFUL' WHERE status='COMPLETED';
 FOR i IN 1..coalesce(array_length(names,1),0) LOOP
  EXECUTE format('ALTER TABLE app_private.withdrawals ADD CONSTRAINT %I %s',names[i],definitions[i]);
 END LOOP;
END $$;
DROP INDEX app_private.withdrawals_reference_idx;
CREATE UNIQUE INDEX withdrawals_reference_idx ON app_private.withdrawals(lower(reference_id)) WHERE status='SUCCESSFUL';
CREATE OR REPLACE FUNCTION app_private.apply_winning_entry() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE expected_amount BIGINT; expected_status TEXT;
BEGIN
    IF NEW.kind <> 'EARNING' THEN
        SELECT amount_paise, status INTO expected_amount, expected_status FROM app_private.withdrawals
            WHERE id=NEW.withdrawal_id AND user_id=NEW.user_id FOR UPDATE;
        IF expected_amount IS DISTINCT FROM NEW.amount_paise OR
           (NEW.kind='RESERVE' AND expected_status<>'PROCESSING') OR
           (NEW.kind='REFUND' AND expected_status<>'REFUNDED') OR
           (NEW.kind='COMPLETE' AND expected_status<>'SUCCESSFUL') THEN
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

-- A terminal result cannot be reopened, even by an accidental direct SQL update.
CREATE FUNCTION app_private.guard_withdrawal_transition() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status IN ('SUCCESSFUL','REFUNDED') AND NEW IS DISTINCT FROM OLD THEN
  RAISE EXCEPTION 'Finalized withdrawals are immutable';
 END IF;
 IF NEW.user_id<>OLD.user_id OR NEW.bank_account_id<>OLD.bank_account_id OR NEW.idempotency_key<>OLD.idempotency_key THEN
  RAISE EXCEPTION 'Withdrawal ownership and destination are immutable';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER withdrawal_transition_guard BEFORE UPDATE ON app_private.withdrawals
FOR EACH ROW EXECUTE FUNCTION app_private.guard_withdrawal_transition();
REVOKE ALL ON FUNCTION app_private.guard_withdrawal_transition() FROM PUBLIC;
CREATE UNIQUE INDEX withdrawal_terminal_ledger_once ON app_private.winning_transactions(withdrawal_id) WHERE kind IN ('COMPLETE','REFUND');
