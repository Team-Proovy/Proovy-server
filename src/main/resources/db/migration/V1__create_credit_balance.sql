CREATE TABLE IF NOT EXISTS credit_balance (
    balance_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    daily_free_credit INTEGER NOT NULL DEFAULT 0,
    daily_free_limit INTEGER NOT NULL DEFAULT 100,
    daily_expires_at TIMESTAMP NULL,
    free_credit INTEGER NOT NULL DEFAULT 0,
    paid_credit INTEGER NOT NULL DEFAULT 0,
    paid_expires_at TIMESTAMP NULL,
    created_at TIMESTAMP NULL,
    updated_at TIMESTAMP NULL,
    CONSTRAINT fk_credit_balance_user
        FOREIGN KEY (user_id) REFERENCES users(user_id)
);
