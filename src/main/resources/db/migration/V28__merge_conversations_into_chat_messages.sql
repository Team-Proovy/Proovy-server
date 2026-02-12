-- 1. chat_messages 테이블에 note_id 컬럼 추가
ALTER TABLE chat_messages
ADD COLUMN note_id BIGINT NULL;

-- 2. note_id에 FK 제약조건 추가
ALTER TABLE chat_messages
ADD CONSTRAINT fk_chat_messages_note
    FOREIGN KEY (note_id) REFERENCES notes(note_id);

-- 3. note_id에 인덱스 추가 (조회 성능 향상)
CREATE INDEX idx_chat_messages_note_id ON chat_messages(note_id);

-- 4. 마이그레이션용 임시 chat_session 생성 (chat_session_id NULL 방지)
-- 각 사용자별로 ACTIVE 세션이 없으면 생성
INSERT INTO chat_sessions (user_id, status, created_at)
SELECT DISTINCT
    n.user_id,
    'ACTIVE',
    NOW()
FROM notes n
WHERE NOT EXISTS (
    SELECT 1 FROM chat_sessions cs
    WHERE cs.user_id = n.user_id
    AND cs.status = 'ACTIVE'
);

-- 5. 기존 conversations/messages 데이터를 chat_messages로 마이그레이션
-- 5-1. USER 메시지 마이그레이션 (중복 방지 강화)
INSERT INTO chat_messages (chat_session_id, role, content, message_type, created_at, note_id)
SELECT
    (SELECT cs.chat_session_id
     FROM chat_sessions cs
     WHERE cs.user_id = n.user_id
     AND cs.status = 'ACTIVE'
     ORDER BY cs.created_at DESC
     LIMIT 1) as chat_session_id,
    m.role::VARCHAR(10),
    jsonb_build_object('text', m.content),
    'text',
    m.created_at,
    c.note_id
FROM messages m
JOIN conversations c ON m.conversation_id = c.conversation_id
JOIN notes n ON c.note_id = n.note_id
WHERE m.role = 'USER'
  -- 중복 방지: note_id, role, created_at 조합으로 체크
  AND NOT EXISTS (
    SELECT 1 FROM chat_messages cm
    WHERE cm.note_id = c.note_id
      AND cm.role = m.role::VARCHAR(10)
      AND cm.created_at = m.created_at
  )
ORDER BY m.created_at;

-- 5-2. ASSISTANT 메시지 마이그레이션 (중복 방지 강화)
INSERT INTO chat_messages (chat_session_id, role, content, message_type, created_at, note_id)
SELECT
    (SELECT cs.chat_session_id
     FROM chat_sessions cs
     WHERE cs.user_id = n.user_id
     AND cs.status = 'ACTIVE'
     ORDER BY cs.created_at DESC
     LIMIT 1) as chat_session_id,
    m.role::VARCHAR(10),
    jsonb_build_object('text', m.content),
    'text',
    m.created_at,
    c.note_id
FROM messages m
JOIN conversations c ON m.conversation_id = c.conversation_id
JOIN notes n ON c.note_id = n.note_id
WHERE m.role = 'ASSISTANT'
  -- 중복 방지: note_id, role, created_at 조합으로 체크
  AND NOT EXISTS (
    SELECT 1 FROM chat_messages cm
    WHERE cm.note_id = c.note_id
      AND cm.role = m.role::VARCHAR(10)
      AND cm.created_at = m.created_at
  )
ORDER BY m.created_at;

-- 6. 마이그레이션 검증 쿼리 (주석 해제하여 확인 가능)
-- SELECT
--     'messages 원본' as source, COUNT(*) as count
-- FROM messages
-- UNION ALL
-- SELECT
--     'chat_messages 마이그레이션' as source, COUNT(*) as count
-- FROM chat_messages
-- WHERE note_id IS NOT NULL;

-- 7. 주석: conversations/messages 테이블은 데이터 백업 및 검증 후 수동으로 삭제
-- 삭제 전 반드시 백업 수행!
-- CREATE TABLE messages_backup AS SELECT * FROM messages;
-- CREATE TABLE conversations_backup AS SELECT * FROM conversations;
--
-- 검증 후 삭제:
-- DROP TABLE IF EXISTS message_tools CASCADE;
-- DROP TABLE IF EXISTS message_assets CASCADE;
-- DROP TABLE IF EXISTS messages CASCADE;
-- DROP TABLE IF EXISTS conversations CASCADE;
