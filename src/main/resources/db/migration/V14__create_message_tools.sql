CREATE TABLE IF NOT EXISTS message_tools (
    id BIGSERIAL PRIMARY KEY,
    message_id BIGINT NOT NULL,
    tool_code VARCHAR(50) NOT NULL,
    CONSTRAINT fk_message_tools_message
        FOREIGN KEY (message_id) REFERENCES messages(message_id)
);
