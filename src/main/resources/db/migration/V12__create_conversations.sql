CREATE TABLE IF NOT EXISTS conversations (
    conversation_id BIGSERIAL PRIMARY KEY,
    note_id BIGINT NOT NULL,
    created_at TIMESTAMP,
    CONSTRAINT fk_conversations_note
        FOREIGN KEY (note_id) REFERENCES notes(note_id)
);
