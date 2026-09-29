ALTER TABLE app_private.withdrawals DROP CONSTRAINT withdrawals_amount_paise_check;
ALTER TABLE app_private.withdrawals ADD CONSTRAINT withdrawals_amount_paise_check CHECK (amount_paise >= 100);
