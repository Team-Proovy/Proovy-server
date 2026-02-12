-- 1. chat_messages 테이블에 note_id 컬럼 추가
ALTER TABLE chat_messages
ADD COLUMN note_id BIGINT NULL;

-- 2. note_id에 FK 제약조건 추가
ALTER TABLE chat_messages
ADD CONSTRAINT fk_chat_messages_note
    FOREIGN KEY (note_id) REFERENCES notes(note_id);

-- 3. note_id에 인덱스 추가 (조회 성능 향상)
CREATE INDEX idx_chat_messages_note_id ON chat_messages(note_id);

-- 4. 기존 conversations/messages 데이터를 chat_messages로 마이그레이션
-- 4-1. USER 메시지 마이그레이션
INSERT INTO chat_messages (chat_session_id, role, content, message_type, created_at, note_id)
SELECT
    cs.chat_session_id,
    m.role::VARCHAR(10),
    jsonb_build_object('text', m.content),
    'text',
    m.created_at,
    c.note_id
FROM messages m
JOIN conversations c ON m.conversation_id = c.conversation_id
JOIN notes n ON c.note_id = n.note_id
LEFT JOIN chat_sessions cs ON cs.user_id = n.user_id AND cs.status = 'ACTIVE'
WHERE m.role = 'USER'
  -- chat_messages에 이미 존재하지 않는 경우만
  AND NOT EXISTS (
    SELECT 1 FROM chat_messages cm
    WHERE cm.note_id = c.note_id
      AND cm.role = m.role::VARCHAR(10)
      AND cm.created_at = m.created_at
  )
ORDER BY m.created_at;

-- 4-2. ASSISTANT 메시지 마이그레이션
INSERT INTO chat_messages (chat_session_id, role, content, message_type, created_at, note_id)
SELECT
    cs.chat_session_id,
    m.role::VARCHAR(10),
    jsonb_build_object('text', m.content),
    'text',
    m.created_at,
    c.note_id
FROM messages m
JOIN conversations c ON m.conversation_id = c.conversation_id
JOIN notes n ON c.note_id = n.note_id
LEFT JOIN chat_sessions cs ON cs.user_id = n.user_id AND cs.status = 'ACTIVE'
WHERE m.role = 'ASSISTANT'
  -- chat_messages에 이미 존재하지 않는 경우만
  AND NOT EXISTS (
    SELECT 1 FROM chat_messages cm
    WHERE cm.note_id = c.note_id
      AND cm.role = m.role::VARCHAR(10)
      AND cm.created_at = m.created_at
  )
ORDER BY m.created_at;

-- 5. 주석: conversations/messages 테이블은 데이터 백업 후 수동으로 삭제
-- DROP TABLE IF EXISTS messages;
-- DROP TABLE IF EXISTS conversations;

