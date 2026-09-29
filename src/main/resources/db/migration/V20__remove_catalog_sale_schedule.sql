-- Historical catalog dates are retained for audit only; active controls availability.
ALTER TABLE public.products ALTER COLUMN start_at DROP NOT NULL;
ALTER TABLE public.products ALTER COLUMN end_at DROP NOT NULL;
