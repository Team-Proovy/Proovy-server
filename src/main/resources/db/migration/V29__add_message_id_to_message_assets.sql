-- ==========================================================
-- V29: message_assets / message_tools FK 컬럼 정합성 복구
-- 문제:
-- - V28에서 message_id -> chat_message_id로 변경되었지만 JPA 엔티티는 message_id를 사용 중
-- - 기존 V29는 chat_messages.id를 참조해 마이그레이션 실패
-- 해결:
-- - 두 테이블을 message_id 기준으로 복구
-- - FK는 chat_messages(chat_message_id)로 연결
-- ==========================================================

-- 1) message_assets 정리
ALTER TABLE message_assets
DROP CONSTRAINT IF EXISTS fk_message_assets_chat_message;

ALTER TABLE message_assets
DROP CONSTRAINT IF EXISTS fk_message_assets_message;

DO $$
DECLARE
    has_chat_message_id BOOLEAN;
    has_message_id BOOLEAN;
BEGIN
    SELECT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'message_assets'
          AND column_name = 'chat_message_id'
    ) INTO has_chat_message_id;

    SELECT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'message_assets'
          AND column_name = 'message_id'
    ) INTO has_message_id;

    IF has_chat_message_id AND has_message_id THEN
        EXECUTE 'UPDATE message_assets SET message_id = chat_message_id WHERE message_id IS NULL';
        EXECUTE 'ALTER TABLE message_assets DROP COLUMN chat_message_id';
    ELSIF has_chat_message_id THEN
        EXECUTE 'ALTER TABLE message_assets RENAME COLUMN chat_message_id TO message_id';
    ELSIF NOT has_message_id THEN
        EXECUTE 'ALTER TABLE message_assets ADD COLUMN message_id BIGINT';
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'message_assets'
          AND column_name = 'message_id'
    ) AND NOT EXISTS (
        SELECT 1 FROM message_assets WHERE message_id IS NULL
    ) THEN
        ALTER TABLE message_assets ALTER COLUMN message_id SET NOT NULL;
    END IF;
END $$;

ALTER TABLE message_assets
ADD CONSTRAINT fk_message_assets_message
FOREIGN KEY (message_id) REFERENCES chat_messages(chat_message_id) ON DELETE CASCADE;

-- 2) message_tools 정리 (엔티티와 스키마 불일치 동시 해결)
ALTER TABLE message_tools
DROP CONSTRAINT IF EXISTS fk_message_tools_chat_message;

ALTER TABLE message_tools
DROP CONSTRAINT IF EXISTS fk_message_tools_message;

DO $$
DECLARE
    has_chat_message_id BOOLEAN;
    has_message_id BOOLEAN;
BEGIN
    SELECT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'message_tools'
          AND column_name = 'chat_message_id'
    ) INTO has_chat_message_id;

    SELECT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'message_tools'
          AND column_name = 'message_id'
    ) INTO has_message_id;

    IF has_chat_message_id AND has_message_id THEN
        EXECUTE 'UPDATE message_tools SET message_id = chat_message_id WHERE message_id IS NULL';
        EXECUTE 'ALTER TABLE message_tools DROP COLUMN chat_message_id';
    ELSIF has_chat_message_id THEN
        EXECUTE 'ALTER TABLE message_tools RENAME COLUMN chat_message_id TO message_id';
    ELSIF NOT has_message_id THEN
        EXECUTE 'ALTER TABLE message_tools ADD COLUMN message_id BIGINT';
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'message_tools'
          AND column_name = 'message_id'
    ) AND NOT EXISTS (
        SELECT 1 FROM message_tools WHERE message_id IS NULL
    ) THEN
        ALTER TABLE message_tools ALTER COLUMN message_id SET NOT NULL;
    END IF;
END $$;

ALTER TABLE message_tools
ADD CONSTRAINT fk_message_tools_message
FOREIGN KEY (message_id) REFERENCES chat_messages(chat_message_id) ON DELETE CASCADE;
