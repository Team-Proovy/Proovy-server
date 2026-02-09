CREATE TABLE IF NOT EXISTS chat_sessions (
    chat_session_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    external_thread_id VARCHAR(100) UNIQUE,
    status VARCHAR(10),
    created_at TIMESTAMP NULL,
    closed_at TIMESTAMP NULL,
    CONSTRAINT fk_chat_sessions_user
        FOREIGN KEY (user_id) REFERENCES users(user_id)
);
