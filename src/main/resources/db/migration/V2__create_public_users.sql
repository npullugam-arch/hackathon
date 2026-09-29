-- Visible in Supabase Table Editor's default public schema; only the backend writes it.
CREATE TABLE IF NOT EXISTS public.users (
    firebase_uid VARCHAR(128) PRIMARY KEY,
    name TEXT,
    email TEXT,
    photo_url TEXT,
    provider TEXT NOT NULL,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    firebase_created_at BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS users_email_idx ON public.users (LOWER(email));
ALTER TABLE public.users ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.users FROM PUBLIC;
-- Supabase can grant these roles access by default. No client access is needed.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
        REVOKE ALL ON public.users FROM anon;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
        REVOKE ALL ON public.users FROM authenticated;
    END IF;
END $$;

-- Preserve accounts already created by the previous backend version.
INSERT INTO public.users (firebase_uid, name, email, photo_url, provider, email_verified,
                          firebase_created_at, created_at, last_login_at, updated_at)
SELECT firebase_uid, display_name, email, photo_url, auth_provider, email_verified,
       firebase_created_at, created_at, to_timestamp(last_sign_in_at / 1000.0), updated_at
FROM app_private.user_profiles
ON CONFLICT (firebase_uid) DO NOTHING;
-- Keep the legacy table intact for recovery. New backend reads/writes use public.users only.
