-- Application-owned data only. Firebase remains the identity provider.
-- This private schema is not part of Supabase's default exposed Data API schemas.
CREATE TABLE app_private.user_profiles (
    firebase_uid VARCHAR(128) PRIMARY KEY,
    email TEXT,
    display_name TEXT,
    photo_url TEXT,
    email_verified BOOLEAN NOT NULL,
    auth_provider TEXT NOT NULL,
    firebase_created_at BIGINT NOT NULL,
    last_sign_in_at BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Email is searchable, but it is mutable and never used to merge distinct Firebase UIDs.
CREATE INDEX user_profiles_email_idx ON app_private.user_profiles (LOWER(email));

REVOKE ALL ON SCHEMA app_private FROM PUBLIC;
REVOKE ALL ON app_private.user_profiles FROM PUBLIC;
ALTER TABLE app_private.user_profiles ENABLE ROW LEVEL SECURITY;
-- No browser policies. The trusted backend connects as the migration/table owner.
