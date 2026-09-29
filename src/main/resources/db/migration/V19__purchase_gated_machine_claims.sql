-- Supersede unclaimed V18 referral offers whose referral was already paid under the old rules.
DO $$ DECLARE c text; BEGIN
 FOR c IN SELECT conname FROM pg_constraint WHERE conrelid='app_private.machine_reward_claims'::regclass AND contype='c' AND pg_get_constraintdef(oid) LIKE '%status%' LOOP
  EXECUTE format('ALTER TABLE app_private.machine_reward_claims DROP CONSTRAINT %I',c);
 END LOOP;
END $$;
ALTER TABLE app_private.machine_reward_claims ADD CONSTRAINT machine_claim_status_check CHECK(status IN ('PENDING','CLAIMED','SUPERSEDED'));
ALTER TABLE app_private.machine_reward_claims ADD CONSTRAINT machine_claim_receipt_check CHECK(
 (status IN ('PENDING','SUPERSEDED') AND ledger_id IS NULL AND claimed_at IS NULL) OR (status='CLAIMED' AND ledger_id IS NOT NULL AND claimed_at IS NOT NULL));
-- Preserve prior credits; do not offer a second payment for an old approved photo.
ALTER TABLE app_private.machine_reward_claims DISABLE TRIGGER machine_reward_claim_validation;
UPDATE app_private.machine_reward_claims c SET status='SUPERSEDED' WHERE c.reward_type='REFERRAL' AND c.status='PENDING' AND EXISTS(SELECT 1 FROM app_private.referral_rewards legacy_reward WHERE legacy_reward.referral_id=c.source_id);
UPDATE app_private.machine_reward_claims c SET status='CLAIMED',ledger_id=t.ledger_id,claimed_at=w.created_at
FROM app_private.photo_tasks t JOIN app_private.winning_transactions w ON w.id=t.ledger_id
WHERE c.reward_type='TAKE_PHOTO' AND c.source_id=t.id AND c.user_id=t.user_id AND c.status='PENDING';
INSERT INTO app_private.machine_reward_claims(id,user_id,reward_type,source_id,amount_paise,status,ledger_id,claimed_at)
SELECT t.id,t.user_id,'TAKE_PHOTO',t.id,2000,'CLAIMED',t.ledger_id,w.created_at
FROM app_private.photo_tasks t JOIN app_private.winning_transactions w ON w.id=t.ledger_id
WHERE t.status='COMPLETED' ON CONFLICT(user_id,reward_type,source_id) DO NOTHING;
ALTER TABLE app_private.machine_reward_claims ENABLE TRIGGER machine_reward_claim_validation;

CREATE OR REPLACE FUNCTION app_private.validate_machine_reward_claim() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'PENDING' OR NEW.ledger_id IS NOT NULL OR NEW.claimed_at IS NOT NULL THEN RAISE EXCEPTION 'Reward must begin unclaimed'; END IF;
  IF NOT EXISTS(SELECT 1 FROM app_private.product_purchases WHERE user_id=NEW.user_id AND payment_status='PAID') THEN RAISE EXCEPTION 'A paid purchase is required'; END IF;
  IF NEW.reward_type='TAKE_PHOTO' AND NOT EXISTS(SELECT 1 FROM app_private.photo_tasks t WHERE t.id=NEW.source_id AND t.user_id=NEW.user_id AND t.status='COMPLETED' AND t.ledger_id IS NULL) THEN RAISE EXCEPTION 'Photo reward requires an approved unpaid task'; END IF;
  IF NEW.reward_type='REFERRAL' AND NOT EXISTS(SELECT 1 FROM app_private.referrals r WHERE r.id=NEW.source_id AND r.inviter_id=NEW.user_id AND r.inviter_id<>r.invitee_id
    AND EXISTS(SELECT 1 FROM app_private.product_purchases p WHERE p.user_id=r.invitee_id AND p.payment_status='PAID' AND p.paid_at>=r.created_at)
    AND NOT EXISTS(SELECT 1 FROM app_private.referral_rewards legacy_reward WHERE legacy_reward.referral_id=r.id)) THEN RAISE EXCEPTION 'Referral needs a qualifying purchase and must not have been rewarded'; END IF;
 ELSE
  IF OLD.status<>'PENDING' OR NEW.status<>'CLAIMED' OR
    (NEW.id,NEW.user_id,NEW.reward_type,NEW.source_id,NEW.amount_paise,NEW.created_at) IS DISTINCT FROM (OLD.id,OLD.user_id,OLD.reward_type,OLD.source_id,OLD.amount_paise,OLD.created_at)
    OR NOT EXISTS(SELECT 1 FROM app_private.product_purchases WHERE user_id=NEW.user_id AND payment_status='PAID')
    OR NOT EXISTS(SELECT 1 FROM app_private.winning_transactions w WHERE w.id=NEW.ledger_id AND w.user_id=NEW.user_id AND w.kind='EARNING' AND w.amount_paise=NEW.amount_paise AND w.source_id='machine-reward:'||NEW.reward_type||':'||NEW.source_id)
    THEN RAISE EXCEPTION 'Invalid reward claim'; END IF;
  IF NEW.reward_type='TAKE_PHOTO' AND EXISTS(SELECT 1 FROM app_private.photo_tasks WHERE id=NEW.source_id AND ledger_id IS NOT NULL) THEN RAISE EXCEPTION 'Photo was already credited'; END IF;
 END IF;
 RETURN NEW;
END $$;

-- Unclaimed V18 referral rewards remain claimable; past automatic payouts stay in history.
-- New qualifying pairs that have not received the old payout are reconciled once during migration.
INSERT INTO app_private.machine_reward_claims(id,user_id,reward_type,source_id,amount_paise)
SELECT r.id,r.inviter_id,'REFERRAL',r.id,3000 FROM app_private.referrals r
WHERE EXISTS(SELECT 1 FROM app_private.product_purchases p WHERE p.user_id=r.inviter_id AND p.payment_status='PAID')
AND EXISTS(SELECT 1 FROM app_private.product_purchases p WHERE p.user_id=r.invitee_id AND p.payment_status='PAID' AND p.paid_at>=r.created_at)
AND NOT EXISTS(SELECT 1 FROM app_private.referral_rewards legacy_reward WHERE legacy_reward.referral_id=r.id)
ON CONFLICT(user_id,reward_type,source_id) DO NOTHING;
INSERT INTO app_private.machine_reward_claims(id,user_id,reward_type,source_id,amount_paise)
SELECT t.id,t.user_id,'TAKE_PHOTO',t.id,2000 FROM app_private.photo_tasks t WHERE t.status='COMPLETED' AND t.ledger_id IS NULL
AND EXISTS(SELECT 1 FROM app_private.product_purchases p WHERE p.user_id=t.user_id AND p.payment_status='PAID')
ON CONFLICT(user_id,reward_type,source_id) DO NOTHING;

CREATE FUNCTION app_private.photo_purchase_gate() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM app_private.product_purchases WHERE user_id=NEW.user_id AND payment_status='PAID') THEN RAISE EXCEPTION 'A paid purchase is required for photo submission'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER photo_purchase_gate BEFORE INSERT ON app_private.photo_tasks FOR EACH ROW EXECUTE FUNCTION app_private.photo_purchase_gate();

-- Rewards are made available automatically in the same transaction that records the qualifying event.
CREATE FUNCTION app_private.offer_machine_rewards() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='photo_tasks' THEN
  IF NEW.status='COMPLETED' AND NEW.ledger_id IS NULL THEN
   INSERT INTO app_private.machine_reward_claims(id,user_id,reward_type,source_id,amount_paise) VALUES(NEW.id,NEW.user_id,'TAKE_PHOTO',NEW.id,2000) ON CONFLICT(user_id,reward_type,source_id) DO NOTHING;
  END IF;
 ELSE
  IF NEW.payment_status='PAID' THEN
   INSERT INTO app_private.machine_reward_claims(id,user_id,reward_type,source_id,amount_paise)
   SELECT r.id,r.inviter_id,'REFERRAL',r.id,3000 FROM app_private.referrals r
   WHERE (r.invitee_id=NEW.user_id OR r.inviter_id=NEW.user_id)
   AND EXISTS(SELECT 1 FROM app_private.product_purchases p WHERE p.user_id=r.inviter_id AND p.payment_status='PAID')
   AND EXISTS(SELECT 1 FROM app_private.product_purchases p WHERE p.user_id=r.invitee_id AND p.payment_status='PAID' AND p.paid_at>=r.created_at)
   AND NOT EXISTS(SELECT 1 FROM app_private.referral_rewards legacy_reward WHERE legacy_reward.referral_id=r.id)
   ON CONFLICT(user_id,reward_type,source_id) DO NOTHING;
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER photo_offer_reward AFTER UPDATE OF status ON app_private.photo_tasks FOR EACH ROW EXECUTE FUNCTION app_private.offer_machine_rewards();
CREATE TRIGGER purchase_offer_machine_reward AFTER INSERT OR UPDATE OF payment_status ON app_private.product_purchases FOR EACH ROW EXECUTE FUNCTION app_private.offer_machine_rewards();
REVOKE ALL ON FUNCTION app_private.photo_purchase_gate(),app_private.offer_machine_rewards() FROM PUBLIC;

ALTER TABLE public.task_machines DROP CONSTRAINT task_machines_task_type_check;
ALTER TABLE public.task_machines ADD CONSTRAINT task_machines_task_type_check CHECK(task_type IS NULL OR task_type IN ('TAKE_PHOTO','REFER_EARN'));
UPDATE public.task_machines SET task_type='REFER_EARN' WHERE id=(SELECT id FROM public.task_machines WHERE task_type IS NULL AND lower(trim(name)) IN ('refer & earn','refer and earn') ORDER BY created_at,id LIMIT 1);
INSERT INTO public.task_machines(id,name,image_url,profile_title,short_description,full_details,active,task_type)
SELECT '00000000-0000-4000-8000-000000000005','Refer & Earn','/assets/machine-2.svg','Referral reward task','Invite a friend and unlock a claim after their qualifying purchase.',
'Purchase any product to unlock Refer & Earn. Share your invitation with a friend. After they register, bind your code and complete their first eligible purchase, claim a one-time INR 30 reward for that friend.',true,'REFER_EARN'
WHERE NOT EXISTS(SELECT 1 FROM public.task_machines WHERE task_type='REFER_EARN');

-- Reject the obsolete auto-credit sources, including requests from an outdated application instance.
CREATE FUNCTION app_private.reject_legacy_machine_credit() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.kind='EARNING' AND (NEW.source_id LIKE 'referral:%' OR NEW.source_id LIKE 'take-photo:%') THEN RAISE EXCEPTION 'Use an approved machine reward claim'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER winning_machine_claim_only BEFORE INSERT ON app_private.winning_transactions FOR EACH ROW EXECUTE FUNCTION app_private.reject_legacy_machine_credit();
REVOKE ALL ON FUNCTION app_private.reject_legacy_machine_credit() FROM PUBLIC;
