-- V29__add_message_id_to_message_assets.sql
ALTER TABLE message_assets ADD COLUMN message_id BIGINT;

ALTER TABLE message_assets 
  ADD CONSTRAINT fk_message_assets_message 
  FOREIGN KEY (message_id) REFERENCES chat_messages(id);