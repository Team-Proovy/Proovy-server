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

-- 4. note_id가 있는 메시지에 대한 content_tsv GIN 인덱스 (검색 시 note_id IS NOT NULL 조건 최적화)
CREATE INDEX IF NOT EXISTS idx_chat_messages_note_id_content_tsv
ON chat_messages USING GIN(content_tsv) WHERE note_id IS NOT NULL;

-- NOTE: 기존 데이터 백필은 ChatMessageTsvBackfillRunner에서 autocommit 모드로 별도 실행됨
-- Flyway 트랜잭션 내에서 배치 처리 시 SKIP LOCKED가 무의미하므로 분리함
