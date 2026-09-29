-- Product purchases are funded by Recharge Balance, while Razorpay remains the wallet recharge provider.
ALTER TABLE app_private.wallet_transactions
    ALTER COLUMN razorpay_order_id DROP NOT NULL,
    ALTER COLUMN razorpay_payment_id DROP NOT NULL;
ALTER TABLE app_private.wallet_transactions ADD COLUMN purchase_id UUID;
ALTER TABLE app_private.wallet_transactions ADD CONSTRAINT wallet_transactions_purchase_fk
    FOREIGN KEY (purchase_id) REFERENCES app_private.product_purchases(id);
DO $$
DECLARE constraint_name text;
BEGIN
    FOR constraint_name IN
        SELECT con.conname
        FROM pg_constraint con
        WHERE con.conrelid = 'app_private.wallet_transactions'::regclass
          AND pg_get_constraintdef(con.oid) LIKE '%transaction_type%'
    LOOP
        EXECUTE format('ALTER TABLE app_private.wallet_transactions DROP CONSTRAINT %I', constraint_name);
    END LOOP;
    FOR constraint_name IN
        SELECT con.conname
        FROM pg_constraint con
        WHERE con.conrelid = 'app_private.wallet_transactions'::regclass
          AND pg_get_constraintdef(con.oid) LIKE '%direction%'
    LOOP
        EXECUTE format('ALTER TABLE app_private.wallet_transactions DROP CONSTRAINT %I', constraint_name);
    END LOOP;
END $$;
ALTER TABLE app_private.wallet_transactions ADD CONSTRAINT wallet_transactions_kind_check
    CHECK ((transaction_type='RECHARGE' AND direction='CREDIT' AND purchase_id IS NULL)
        OR (transaction_type='PURCHASE' AND direction='DEBIT' AND purchase_id IS NOT NULL));

DO $$
DECLARE constraint_name text;
BEGIN
    FOR constraint_name IN
        SELECT con.conname
        FROM pg_constraint con
        WHERE con.conrelid = 'app_private.product_purchases'::regclass
          AND pg_get_constraintdef(con.oid) LIKE '%razorpay_payment_id%'
    LOOP
        EXECUTE format('ALTER TABLE app_private.product_purchases DROP CONSTRAINT %I', constraint_name);
    END LOOP;
END $$;
ALTER TABLE app_private.product_purchases ADD CONSTRAINT product_purchases_paid_state_check
    CHECK ((payment_status='PAID') = (paid_at IS NOT NULL));
ALTER TABLE app_private.product_purchases ADD CONSTRAINT product_purchases_paid_lifecycle_check
    CHECK (payment_status<>'PAID' OR (first_claim_at IS NOT NULL AND end_at>first_claim_at));
