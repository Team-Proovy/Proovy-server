-- ==========================================================
-- V28: Conversation/Message 테이블을 ChatMessage로 통합
-- ==========================================================

-- 1. chat_messages 테이블에 note_id 컬럼 추가 (노트와 직접 연결)
ALTER TABLE chat_messages 
ADD COLUMN IF NOT EXISTS note_id BIGINT;

-- note_id FK 제약조건 추가
ALTER TABLE chat_messages 
ADD CONSTRAINT fk_chat_messages_note 
FOREIGN KEY (note_id) REFERENCES notes(note_id) ON DELETE CASCADE;

-- note_id 인덱스 추가
CREATE INDEX IF NOT EXISTS idx_chat_messages_note_id 
ON chat_messages(note_id);

CREATE INDEX IF NOT EXISTS idx_chat_messages_note_id_created_at 
ON chat_messages(note_id, created_at);

-- 2. chat_messages 테이블에 status 컬럼 추가
ALTER TABLE chat_messages 
ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'completed';

-- 3. message_assets 테이블 변경 (message_id -> chat_message_id)
-- 기존 FK 제약조건 삭제
ALTER TABLE message_assets 
DROP CONSTRAINT IF EXISTS fk_message_assets_message;

-- 컬럼명 변경
ALTER TABLE message_assets 
RENAME COLUMN message_id TO chat_message_id;

-- 새 FK 제약조건 추가 (chat_messages 참조)
ALTER TABLE message_assets 
ADD CONSTRAINT fk_message_assets_chat_message 
FOREIGN KEY (chat_message_id) REFERENCES chat_messages(chat_message_id) ON DELETE CASCADE;

-- 4. message_tools 테이블 변경 (message_id -> chat_message_id)
-- 기존 FK 제약조건 삭제
ALTER TABLE message_tools 
DROP CONSTRAINT IF EXISTS fk_message_tools_message;

-- 컬럼명 변경
ALTER TABLE message_tools 
RENAME COLUMN message_id TO chat_message_id;

-- 새 FK 제약조건 추가 (chat_messages 참조)
ALTER TABLE message_tools 
ADD CONSTRAINT fk_message_tools_chat_message 
FOREIGN KEY (chat_message_id) REFERENCES chat_messages(chat_message_id) ON DELETE CASCADE;

-- 5. Full-Text Search를 위한 tsvector 컬럼 추가 (content JSONB에서 텍스트 추출)
ALTER TABLE chat_messages 
ADD COLUMN IF NOT EXISTS content_tsv tsvector;

-- tsvector 인덱스 생성
CREATE INDEX IF NOT EXISTS idx_chat_messages_content_tsv 
ON chat_messages USING GIN(content_tsv);

-- pg_trgm 확장 활성화 (한글 검색용)
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 6. 기존 테이블 삭제 (데이터 이관이 완료된 경우에만 실행)
-- 주의: 기존 데이터가 있는 경우 먼저 데이터를 chat_messages로 이관해야 합니다.
-- 아래는 참조 관계로 인해 messages -> conversations 순서로 삭제해야 합니다.

-- messages 테이블 삭제
DROP TABLE IF EXISTS messages CASCADE;

-- conversations 테이블 삭제
DROP TABLE IF EXISTS conversations CASCADE;
