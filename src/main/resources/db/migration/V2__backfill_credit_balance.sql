INSERT INTO credit_balance (
    user_id,
    daily_free_credit,
    daily_free_limit,
    daily_expires_at,
    free_credit,
    paid_credit,
    created_at,
    updated_at
)
SELECT
    u.user_id,
    100,
    100,
    date_trunc('day', now()) + interval '1 day',
    0,
    0,
    now(),
    now()
FROM users u
WHERE NOT EXISTS (
    SELECT 1
    FROM credit_balance cb
    WHERE cb.user_id = u.user_id
);
