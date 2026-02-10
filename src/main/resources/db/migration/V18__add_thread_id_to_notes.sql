-- Note 테이블에 thread_id 컬럼 추가 (멀티턴 대화 지원)
ALTER TABLE notes
    ADD COLUMN thread_id VARCHAR(100) UNIQUE;

-- 인덱스 생성 (thread_id로 조회 시 성능 향상)
CREATE INDEX idx_notes_thread_id ON notes(thread_id);

COMMENT ON COLUMN notes.thread_id IS 'Proovy-ai LangGraph checkpointer와 연동할 대화 맥락 식별자';
