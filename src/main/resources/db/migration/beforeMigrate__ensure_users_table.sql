CREATE TABLE IF NOT EXISTS users (
    user_id BIGSERIAL PRIMARY KEY,
    name VARCHAR(50),
    nickname VARCHAR(30) NOT NULL,
    department VARCHAR(100),
    referral_source VARCHAR(20),
    provider VARCHAR(20),
    provider_user_id VARCHAR(100),
    email VARCHAR(255),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_user_provider ON users(provider, provider_user_id);
