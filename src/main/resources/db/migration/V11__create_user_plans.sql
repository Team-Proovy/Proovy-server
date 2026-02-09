CREATE TABLE IF NOT EXISTS user_plans (
    user_plan_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    plan_type VARCHAR(10) NOT NULL DEFAULT 'FREE',
    started_at TIMESTAMP,
    expired_at TIMESTAMP,
    is_active BOOLEAN DEFAULT true,
    CONSTRAINT fk_user_plans_user
        FOREIGN KEY (user_id) REFERENCES users(user_id)
);
