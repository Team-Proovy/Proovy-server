-- ==========================================================
-- V30: chat_messages Full-Text Search 수정
-- 문제: content_tsv 트리거 누락, 인덱스 미활용
-- ==========================================================

-- 1. content_tsv 자동 업데이트 트리거 함수 생성
-- JSONB content에서 'text' 필드를 추출하여 tsvector 생성
CREATE OR REPLACE FUNCTION chat_messages_content_tsv_update()
RETURNS TRIGGER AS $$
BEGIN
    -- content JSONB에서 'text' 필드 추출 후 tsvector 생성
    -- 'simple' 설정: 언어 의존성 없이 토큰화 (한글 호환)
    NEW.content_tsv := to_tsvector('simple', COALESCE(NEW.content->>'text', ''));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 2. 트리거 생성 (INSERT/UPDATE 시 자동 업데이트)
DROP TRIGGER IF EXISTS trg_chat_messages_content_tsv ON chat_messages;
CREATE TRIGGER trg_chat_messages_content_tsv
    BEFORE INSERT OR UPDATE OF content ON chat_messages
    FOR EACH ROW
    EXECUTE FUNCTION chat_messages_content_tsv_update();

-- 3. pg_trgm 인덱스 추가 (한글 ILIKE 검색 최적화)
-- content->>'text'에 대한 표현식 인덱스
CREATE INDEX IF NOT EXISTS idx_chat_messages_content_text_trgm
ON chat_messages USING GIN((content->>'text') gin_trgm_ops);

-- 4. 기존 데이터 백필 (배치 처리로 테이블 락 방지)
-- 500건씩 배치로 처리하여 장시간 락 방지 및 502 에러 방지
DO $$
DECLARE
    batch_size INT := 500;
    updated_count INT;
    total_updated INT := 0;
BEGIN
    RAISE NOTICE 'Starting content_tsv backfill...';

    LOOP
        UPDATE chat_messages
        SET content_tsv = to_tsvector('simple', COALESCE(content->>'text', ''))
        WHERE chat_message_id IN (
            SELECT chat_message_id FROM chat_messages
            WHERE content_tsv IS NULL
            LIMIT batch_size
            FOR UPDATE SKIP LOCKED
        );

        GET DIAGNOSTICS updated_count = ROW_COUNT;
        total_updated := total_updated + updated_count;

        -- 더 이상 업데이트할 행이 없으면 종료
        EXIT WHEN updated_count = 0;

        -- 각 배치 후 짧은 대기 (다른 트랜잭션에 기회 제공)
        PERFORM pg_sleep(0.05);
    END LOOP;

    RAISE NOTICE 'Backfill completed. Total updated: %', total_updated;
END $$;

-- 5. 검색 성능을 위한 복합 인덱스 (user_id를 통한 필터링 최적화)
CREATE INDEX IF NOT EXISTS idx_chat_messages_note_id_content_tsv
ON chat_messages USING GIN(content_tsv) WHERE note_id IS NOT NULL;
