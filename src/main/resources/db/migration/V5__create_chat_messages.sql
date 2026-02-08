CREATE TABLE IF NOT EXISTS chat_messages (
    chat_message_id BIGSERIAL PRIMARY KEY,
    chat_session_id BIGINT NOT NULL,
    role VARCHAR(10) NOT NULL,
    content JSONB,
    message_type VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NULL,
    CONSTRAINT fk_chat_messages_session
        FOREIGN KEY (chat_session_id) REFERENCES chat_sessions(chat_session_id)
);
