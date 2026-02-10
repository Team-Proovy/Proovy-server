-- =============================================
-- V18: PostgreSQL Full-Text Search 최적화
-- messages 테이블에 tsvector 컬럼 및 GIN 인덱스 추가
-- =============================================

-- pg_trgm 확장 설치 (한글 검색 지원)
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- messages 테이블에 search_vector 컬럼 추가
ALTER TABLE messages ADD COLUMN IF NOT EXISTS search_vector tsvector;

-- GIN 인덱스 생성 (Full-Text Search용)
CREATE INDEX IF NOT EXISTS idx_messages_search_vector
ON messages USING GIN(search_vector);

-- pg_trgm GIN 인덱스 (한글 LIKE 검색 최적화)
CREATE INDEX IF NOT EXISTS idx_messages_content_trgm
ON messages USING GIN(content gin_trgm_ops);

-- tsvector 자동 업데이트 함수
CREATE OR REPLACE FUNCTION messages_search_vector_update()
RETURNS TRIGGER AS $$
BEGIN
    -- 'simple' 설정: 언어 의존성 없이 토큰화 (한글 호환)
    NEW.search_vector := to_tsvector('simple', COALESCE(NEW.content, ''));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 트리거 생성 (INSERT/UPDATE 시 자동 업데이트)
DROP TRIGGER IF EXISTS trg_messages_search_vector ON messages;
CREATE TRIGGER trg_messages_search_vector
    BEFORE INSERT OR UPDATE OF content ON messages
    FOR EACH ROW
    EXECUTE FUNCTION messages_search_vector_update();

-- 기존 데이터에 대한 search_vector 업데이트
UPDATE messages
SET search_vector = to_tsvector('simple', COALESCE(content, ''))
WHERE search_vector IS NULL;

-- 복합 인덱스: conversation_id + role (자주 사용되는 필터 조합)
CREATE INDEX IF NOT EXISTS idx_messages_conversation_role
ON messages(conversation_id, role);
