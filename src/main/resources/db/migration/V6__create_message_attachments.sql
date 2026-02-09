CREATE TABLE IF NOT EXISTS message_attachments (
    message_attachment_id BIGSERIAL PRIMARY KEY,
    chat_message_id BIGINT NOT NULL,
    file_name VARCHAR(255),
    mime_type VARCHAR(100),
    storage_key VARCHAR(500),
    source VARCHAR(20) NOT NULL,
    metadata JSONB,
    created_at TIMESTAMP NULL,
    CONSTRAINT fk_message_attachments_message
        FOREIGN KEY (chat_message_id) REFERENCES chat_messages(chat_message_id)
);
