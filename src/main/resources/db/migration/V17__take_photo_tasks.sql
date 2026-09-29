ALTER TABLE public.task_machines ADD COLUMN task_type VARCHAR(32) CHECK (task_type IS NULL OR task_type='TAKE_PHOTO');
CREATE UNIQUE INDEX task_machine_type_unique ON public.task_machines(task_type) WHERE task_type IS NOT NULL;
UPDATE public.task_machines SET task_type='TAKE_PHOTO' WHERE id=(SELECT id FROM public.task_machines WHERE lower(trim(name))='take photo' ORDER BY created_at,id LIMIT 1);
INSERT INTO public.task_machines(id,name,image_url,profile_title,short_description,full_details,active,task_type)
SELECT '00000000-0000-4000-8000-000000000004','Take Photo','/assets/machine-1.svg','Photo verification task',
 'Upload a photo for verification and receive a one-time reward after approval.',
 'Upload a photo while using the Launchpad on your mobile, then submit it for verification.',true,'TAKE_PHOTO'
WHERE NOT EXISTS (SELECT 1 FROM public.task_machines WHERE task_type='TAKE_PHOTO');

CREATE TABLE app_private.photo_tasks (
 id UUID PRIMARY KEY, machine_id UUID NOT NULL REFERENCES public.task_machines(id),
 user_id VARCHAR(128) NOT NULL REFERENCES public.users(firebase_uid), request_id UUID NOT NULL,
 photo_url TEXT NOT NULL, cloudinary_public_id VARCHAR(200) NOT NULL UNIQUE, photo_sha256 CHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','COMPLETED','REJECTED')),
 reward_paise BIGINT NOT NULL DEFAULT 2000 CHECK(reward_paise=2000),
 submitted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, reviewed_at TIMESTAMPTZ,
 reviewed_by VARCHAR(254), rejection_reason VARCHAR(1000),
 ledger_id UUID UNIQUE REFERENCES app_private.winning_transactions(id),
 UNIQUE(user_id,request_id),
 CHECK ((status='PENDING' AND reviewed_at IS NULL AND reviewed_by IS NULL AND rejection_reason IS NULL AND ledger_id IS NULL)
 OR (status='COMPLETED' AND reviewed_at IS NOT NULL AND reviewed_by IS NOT NULL AND rejection_reason IS NULL AND ledger_id IS NOT NULL)
 OR (status='REJECTED' AND reviewed_at IS NOT NULL AND reviewed_by IS NOT NULL AND length(trim(rejection_reason))>0 AND rejection_reason IS NOT NULL AND ledger_id IS NULL))
);
CREATE UNIQUE INDEX photo_task_one_reward ON app_private.photo_tasks(user_id,machine_id) WHERE status IN ('PENDING','COMPLETED');
CREATE INDEX photo_task_review_queue ON app_private.photo_tasks(status,submitted_at DESC,id);
CREATE INDEX photo_task_user_history ON app_private.photo_tasks(user_id,submitted_at DESC,id);

CREATE FUNCTION app_private.validate_photo_task() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'PENDING' OR NOT EXISTS(SELECT 1 FROM public.task_machines WHERE id=NEW.machine_id AND task_type='TAKE_PHOTO' AND active) THEN RAISE EXCEPTION 'Invalid photo task'; END IF;
 ELSE
  IF OLD.status<>'PENDING' OR NEW.status NOT IN ('COMPLETED','REJECTED') OR
    (NEW.id,NEW.machine_id,NEW.user_id,NEW.request_id,NEW.photo_url,NEW.cloudinary_public_id,NEW.photo_sha256,NEW.reward_paise,NEW.submitted_at)
    IS DISTINCT FROM (OLD.id,OLD.machine_id,OLD.user_id,OLD.request_id,OLD.photo_url,OLD.cloudinary_public_id,OLD.photo_sha256,OLD.reward_paise,OLD.submitted_at)
    THEN RAISE EXCEPTION 'Photo task is immutable except for one final review'; END IF;
 END IF;
 IF NEW.status='COMPLETED' AND NOT EXISTS(SELECT 1 FROM app_private.winning_transactions
  WHERE id=NEW.ledger_id AND user_id=NEW.user_id AND kind='EARNING' AND amount_paise=2000 AND source_id='take-photo:'||NEW.id)
  THEN RAISE EXCEPTION 'Photo task credit mismatch'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER photo_task_validation BEFORE INSERT OR UPDATE ON app_private.photo_tasks FOR EACH ROW EXECUTE FUNCTION app_private.validate_photo_task();
CREATE TRIGGER photo_task_no_delete BEFORE DELETE ON app_private.photo_tasks FOR EACH ROW EXECUTE FUNCTION app_private.reject_ledger_mutation();
CREATE FUNCTION app_private.require_photo_receipt() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.kind='EARNING' AND NEW.source_id LIKE 'take-photo:%' AND NOT EXISTS(SELECT 1 FROM app_private.photo_tasks WHERE ledger_id=NEW.id AND status='COMPLETED') THEN
  RAISE EXCEPTION 'Photo task receipt missing';
 END IF;
 RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER photo_credit_receipt AFTER INSERT ON app_private.winning_transactions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION app_private.require_photo_receipt();
ALTER TABLE app_private.photo_tasks ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON app_private.photo_tasks FROM PUBLIC;
REVOKE ALL ON FUNCTION app_private.validate_photo_task(),app_private.require_photo_receipt() FROM PUBLIC;
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='anon') THEN REVOKE ALL ON app_private.photo_tasks FROM anon; END IF;
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='authenticated') THEN REVOKE ALL ON app_private.photo_tasks FROM authenticated; END IF;
END $$;
