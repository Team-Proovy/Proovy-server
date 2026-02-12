ALTER TABLE credit_balance
    ADD COLUMN IF NOT EXISTS last_monthly_grant_plan_id BIGINT;
