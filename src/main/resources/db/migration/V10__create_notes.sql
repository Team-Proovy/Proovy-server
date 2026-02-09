CREATE TABLE IF NOT EXISTS notes (
    note_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    content_md TEXT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT fk_notes_user
        FOREIGN KEY (user_id) REFERENCES users(user_id)
);
