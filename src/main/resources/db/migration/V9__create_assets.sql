CREATE TABLE IF NOT EXISTS assets (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    note_id BIGINT NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_size BIGINT NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    s3_key VARCHAR(500) NOT NULL,
    thumbnail_s3_key VARCHAR(500),
    source VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    upload_expires_at TIMESTAMP,
    total_pages INTEGER,
    ocr_status VARCHAR(20),
    ocr_text TEXT,
    ocr_processed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    version BIGINT
);

CREATE INDEX IF NOT EXISTS idx_assets_user_id ON assets(user_id);
CREATE INDEX IF NOT EXISTS idx_assets_note_id ON assets(note_id);
