CREATE TABLE IF NOT EXISTS public.task_machines (
 id UUID PRIMARY KEY, name VARCHAR(160) NOT NULL, image_url TEXT NOT NULL,
 profile_title VARCHAR(160) NOT NULL, short_description VARCHAR(500) NOT NULL,
 full_details TEXT NOT NULL, active BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS task_machines_active_idx ON public.task_machines(active,created_at);
ALTER TABLE public.task_machines ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.task_machines FROM PUBLIC;
DO $$ BEGIN
 IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='anon') THEN REVOKE ALL ON public.task_machines FROM anon; END IF;
 IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='authenticated') THEN REVOKE ALL ON public.task_machines FROM authenticated; END IF;
END $$;
INSERT INTO public.task_machines(id,name,image_url,profile_title,short_description,full_details,active)
SELECT ('00000000-0000-4000-8000-00000000000' || n)::uuid,'Machine ' || n,
 '/assets/machine-' || n || '.svg','Task Bonus preview',
 'Discover a new addition to your Task Bonus collection.',
 'This machine is a preview. More details and functionality will be announced here.',true
FROM generate_series(1,3) AS n ON CONFLICT(id) DO NOTHING;
