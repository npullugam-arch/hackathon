DO $$
DECLARE constraint_name text;
BEGIN
    FOR constraint_name IN
        SELECT con.conname
        FROM pg_constraint con
        WHERE con.conrelid='app_private.photo_tasks'::regclass
          AND con.contype='c' AND pg_get_constraintdef(con.oid) LIKE '%ledger_id IS NOT NULL%'
    LOOP
        EXECUTE format('ALTER TABLE app_private.photo_tasks DROP CONSTRAINT %I',constraint_name);
    END LOOP;
END $$;

ALTER TABLE app_private.photo_tasks ADD CONSTRAINT photo_task_review_state_check CHECK (
    (status='PENDING' AND reviewed_at IS NULL AND reviewed_by IS NULL AND rejection_reason IS NULL AND ledger_id IS NULL)
    OR (status='COMPLETED' AND reviewed_at IS NOT NULL AND reviewed_by IS NOT NULL AND rejection_reason IS NULL)
    OR (status='REJECTED' AND reviewed_at IS NOT NULL AND reviewed_by IS NOT NULL AND length(trim(rejection_reason))>0 AND rejection_reason IS NOT NULL AND ledger_id IS NULL)
);

CREATE OR REPLACE FUNCTION app_private.validate_photo_task() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'PENDING' OR NOT EXISTS(SELECT 1 FROM public.task_machines WHERE id=NEW.machine_id AND task_type='TAKE_PHOTO' AND active) THEN RAISE EXCEPTION 'Invalid photo task'; END IF;
 ELSE
  IF OLD.status<>'PENDING' OR NEW.status NOT IN ('COMPLETED','REJECTED') OR
    (NEW.id,NEW.machine_id,NEW.user_id,NEW.request_id,NEW.photo_url,NEW.cloudinary_public_id,NEW.photo_sha256,NEW.reward_paise,NEW.submitted_at)
    IS DISTINCT FROM (OLD.id,OLD.machine_id,OLD.user_id,OLD.request_id,OLD.photo_url,OLD.cloudinary_public_id,OLD.photo_sha256,OLD.reward_paise,OLD.submitted_at)
    THEN RAISE EXCEPTION 'Photo task is immutable except for one final review'; END IF;
 END IF;
 IF NEW.ledger_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM app_private.winning_transactions
  WHERE id=NEW.ledger_id AND user_id=NEW.user_id AND kind='EARNING' AND amount_paise=2000 AND source_id='take-photo:'||NEW.id)
  THEN RAISE EXCEPTION 'Photo task credit mismatch'; END IF;
 RETURN NEW;
END $$;

CREATE TABLE app_private.machine_reward_claims (
    id UUID PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL REFERENCES public.users(firebase_uid),
    reward_type VARCHAR(16) NOT NULL CHECK(reward_type IN ('TAKE_PHOTO','REFERRAL')),
    source_id UUID NOT NULL,
    amount_paise BIGINT NOT NULL CHECK((reward_type='TAKE_PHOTO' AND amount_paise=2000) OR (reward_type='REFERRAL' AND amount_paise=3000)),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','CLAIMED')),
    ledger_id UUID UNIQUE REFERENCES app_private.winning_transactions(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claimed_at TIMESTAMPTZ,
    UNIQUE(user_id,reward_type,source_id),
    CHECK((status='PENDING' AND ledger_id IS NULL AND claimed_at IS NULL) OR (status='CLAIMED' AND ledger_id IS NOT NULL AND claimed_at IS NOT NULL))
);
CREATE INDEX machine_reward_claim_user_idx ON app_private.machine_reward_claims(user_id,created_at DESC);

CREATE FUNCTION app_private.validate_machine_reward_claim() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'PENDING' OR NEW.ledger_id IS NOT NULL OR NEW.claimed_at IS NOT NULL THEN RAISE EXCEPTION 'Machine reward must begin pending'; END IF;
  IF NEW.reward_type='TAKE_PHOTO' AND NOT EXISTS(
      SELECT 1 FROM app_private.photo_tasks t WHERE t.id=NEW.source_id AND t.user_id=NEW.user_id AND t.status='COMPLETED' AND t.reward_paise=NEW.amount_paise
  ) THEN RAISE EXCEPTION 'Photo reward requires an approved task'; END IF;
  IF NEW.reward_type='REFERRAL' AND NOT EXISTS(
      SELECT 1 FROM app_private.referrals r
      WHERE r.id=NEW.source_id AND r.inviter_id=NEW.user_id
        AND EXISTS(SELECT 1 FROM app_private.product_purchases p WHERE p.user_id=r.inviter_id AND p.payment_status='PAID')
        AND EXISTS(SELECT 1 FROM app_private.product_purchases p WHERE p.user_id=r.invitee_id AND p.payment_status='PAID')
  ) THEN RAISE EXCEPTION 'Referral reward requires eligible purchased products'; END IF;
 ELSE
  IF OLD.status<>'PENDING' OR NEW.status<>'CLAIMED' OR
     (NEW.id,NEW.user_id,NEW.reward_type,NEW.source_id,NEW.amount_paise,NEW.created_at) IS DISTINCT FROM
     (OLD.id,OLD.user_id,OLD.reward_type,OLD.source_id,OLD.amount_paise,OLD.created_at) OR
     NOT EXISTS(SELECT 1 FROM app_private.winning_transactions w WHERE w.id=NEW.ledger_id AND w.user_id=NEW.user_id
         AND w.kind='EARNING' AND w.amount_paise=NEW.amount_paise
         AND w.source_id='machine-reward:'||NEW.reward_type||':'||NEW.source_id)
     THEN RAISE EXCEPTION 'Machine reward is immutable except for one valid claim'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER machine_reward_claim_validation BEFORE INSERT OR UPDATE ON app_private.machine_reward_claims
    FOR EACH ROW EXECUTE FUNCTION app_private.validate_machine_reward_claim();
CREATE TRIGGER machine_reward_claim_no_delete BEFORE DELETE ON app_private.machine_reward_claims
    FOR EACH ROW EXECUTE FUNCTION app_private.reject_ledger_mutation();

CREATE FUNCTION app_private.require_machine_reward_receipt() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.kind='EARNING' AND NEW.source_id LIKE 'machine-reward:%' AND NOT EXISTS(
     SELECT 1 FROM app_private.machine_reward_claims c WHERE c.ledger_id=NEW.id AND c.user_id=NEW.user_id
       AND c.amount_paise=NEW.amount_paise AND c.status='CLAIMED'
 ) THEN RAISE EXCEPTION 'Machine reward claim receipt missing'; END IF;
 RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER machine_reward_credit_receipt AFTER INSERT ON app_private.winning_transactions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION app_private.require_machine_reward_receipt();

ALTER TABLE app_private.machine_reward_claims ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON app_private.machine_reward_claims FROM PUBLIC;
REVOKE ALL ON FUNCTION app_private.validate_machine_reward_claim(),app_private.require_machine_reward_receipt() FROM PUBLIC;
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='anon') THEN REVOKE ALL ON app_private.machine_reward_claims FROM anon; END IF;
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='authenticated') THEN REVOKE ALL ON app_private.machine_reward_claims FROM authenticated; END IF;
END $$;