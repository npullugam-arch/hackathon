ALTER TABLE public.products ADD COLUMN IF NOT EXISTS country_name varchar(120);
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS country_url varchar(2048);
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS tag varchar(40);
