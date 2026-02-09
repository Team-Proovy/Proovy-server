CREATE TABLE IF NOT EXISTS message_assets (
    id BIGSERIAL PRIMARY KEY,
    message_id BIGINT NOT NULL,
    asset_id BIGINT NOT NULL,
    CONSTRAINT fk_message_assets_message
        FOREIGN KEY (message_id) REFERENCES messages(message_id),
    CONSTRAINT fk_message_assets_asset
        FOREIGN KEY (asset_id) REFERENCES assets(id)
);
