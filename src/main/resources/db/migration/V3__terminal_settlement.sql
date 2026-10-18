-- =====================================================================
-- V3: terminal 확정 (task 1.82, #221)
--
-- 1. chat_messages.verification_summary
--    완료 assistant 메시지의 공개 검증 요약(terminal-v2 verification_summary, snake_case). 대화 조회·replay가
--    canonical 응답과 함께 돌려준다. summary는 page·claim 수에 비례해 커질 수 있어 event payload(16KB 상한)가 아니라
--    메시지에 둔다. NULL은 빈 배열과 같다(이 열 이전에 직접 만든 완료 메시지 포함).
-- 2. ai_runs.terminal_source_sha256
--    run을 확정한 Brain terminal({type, payload}의 canonical JSON sha256). 같은 source_event_id 재전달이 같은
--    결과인지(DUPLICATE) 바뀐 결과인지(CONFLICTING_DUPLICATE) 가른다. Server가 만든 실패(deadline 등)는 NULL이다.
--    terminal 뒤에는 ai_runs_guard_update가 열 전체를 고정하므로 전환 UPDATE에서만 쓸 수 있다.
-- =====================================================================

ALTER TABLE chat_messages ADD COLUMN verification_summary JSONB;

ALTER TABLE chat_messages ADD CONSTRAINT ck_chat_messages_verification_summary CHECK (
    verification_summary IS NULL
    OR (role = 'ASSISTANT' AND status = 'COMPLETED' AND jsonb_typeof(verification_summary) = 'array')
);

ALTER TABLE ai_runs ADD COLUMN terminal_source_sha256 VARCHAR(64)
    CONSTRAINT ck_ai_runs_terminal_source_sha256 CHECK (terminal_source_sha256 ~ '^[0-9a-f]{64}$');

ALTER TABLE ai_runs ADD CONSTRAINT ck_ai_runs_terminal_source_status CHECK (
    terminal_source_sha256 IS NULL OR status IN ('COMPLETED', 'FAILED')
);
