CREATE TABLE IF NOT EXISTS public.products (
    id UUID PRIMARY KEY,
    title VARCHAR(160) NOT NULL,
    image_url TEXT NOT NULL,
    original_price NUMERIC(14,2) NOT NULL CHECK (original_price >= 0),
    discount_price NUMERIC(14,2) NOT NULL CHECK (discount_price >= 0 AND discount_price <= original_price),
    duration_days INTEGER NOT NULL CHECK (duration_days BETWEEN 1 AND 3650),
    daily_income NUMERIC(14,2) NOT NULL CHECK (daily_income >= 0),
    total_earnings NUMERIC(18,2) NOT NULL CHECK (total_earnings >= 0),
    description TEXT NOT NULL,
    start_at TIMESTAMPTZ NOT NULL,
    end_at TIMESTAMPTZ NOT NULL CHECK (end_at > start_at),
    active BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS products_active_created_idx ON public.products (active, created_at DESC);
CREATE TABLE IF NOT EXISTS public.advertisements (
    id UUID PRIMARY KEY,
    title VARCHAR(160) NOT NULL,
    image_url TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS one_active_advertisement ON public.advertisements ((TRUE)) WHERE active;
ALTER TABLE public.products ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.advertisements ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.products, public.advertisements FROM PUBLIC;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='anon') THEN
        REVOKE ALL ON public.products, public.advertisements FROM anon;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='authenticated') THEN
        REVOKE ALL ON public.products, public.advertisements FROM authenticated;
    END IF;
END $$;
