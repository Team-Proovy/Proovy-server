CREATE TABLE IF NOT EXISTS credit_history (
    history_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    event_name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    amount INTEGER NOT NULL,
    change_type VARCHAR(10) NOT NULL,
    credit_type VARCHAR(10) NOT NULL,
    balance_after_daily INTEGER NOT NULL,
    balance_after_free INTEGER NOT NULL,
    balance_after_paid INTEGER NOT NULL,
    created_at TIMESTAMP NULL,
    CONSTRAINT fk_credit_history_user
        FOREIGN KEY (user_id) REFERENCES users(user_id)
);

CREATE INDEX IF NOT EXISTS idx_credit_history_user_created ON credit_history(user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_credit_history_change_type ON credit_history(change_type);
CREATE INDEX IF NOT EXISTS idx_credit_history_credit_type ON credit_history(credit_type);
