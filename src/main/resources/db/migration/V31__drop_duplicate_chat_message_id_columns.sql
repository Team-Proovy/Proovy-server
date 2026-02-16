-- ==========================================================
-- V31: message_assets / message_tools 중복 chat_message_id 컬럼 제거
-- 문제:
-- - V29에서 chat_message_id → message_id로 정리해야 했으나
--   두 컬럼이 동시에 남아있는 환경이 존재
-- - JPA 엔티티는 message_id를 사용하지만, chat_message_id에
--   NOT NULL 제약이 걸려있어 INSERT 시 에러 발생
-- 해결:
-- - chat_message_id 컬럼이 아직 남아있으면 데이터를 message_id로
--   복사한 뒤 drop
-- ==========================================================

-- 1) message_assets 정리
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'message_assets'
          AND column_name = 'chat_message_id'
    ) THEN
        -- message_id가 null인 행에 chat_message_id 값 복사
        UPDATE message_assets
        SET message_id = chat_message_id
        WHERE message_id IS NULL AND chat_message_id IS NOT NULL;

        -- FK 제약 제거 (있으면)
        ALTER TABLE message_assets
        DROP CONSTRAINT IF EXISTS fk_message_assets_chat_message;

        -- 중복 컬럼 제거
        ALTER TABLE message_assets DROP COLUMN chat_message_id;

        RAISE NOTICE 'message_assets: chat_message_id 컬럼 제거 완료';
    END IF;
END $$;

-- 2) message_tools 정리
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'message_tools'
          AND column_name = 'chat_message_id'
    ) THEN
        UPDATE message_tools
        SET message_id = chat_message_id
        WHERE message_id IS NULL AND chat_message_id IS NOT NULL;

        ALTER TABLE message_tools
        DROP CONSTRAINT IF EXISTS fk_message_tools_chat_message;

        ALTER TABLE message_tools DROP COLUMN chat_message_id;

        RAISE NOTICE 'message_tools: chat_message_id 컬럼 제거 완료';
    END IF;
END $$;

-- 3) message_id NOT NULL 보장
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM message_assets WHERE message_id IS NULL
    ) THEN
        EXECUTE 'ALTER TABLE message_assets ALTER COLUMN message_id SET NOT NULL';
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM message_tools WHERE message_id IS NULL
    ) THEN
        EXECUTE 'ALTER TABLE message_tools ALTER COLUMN message_id SET NOT NULL';
    END IF;
END $$;
