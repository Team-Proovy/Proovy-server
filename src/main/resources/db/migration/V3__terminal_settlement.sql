-- =====================================================================
-- V3: terminal 확정 (task 1.82, #221)
--
-- 1. chat_messages.verification_summary
--    완료 assistant 메시지의 공개 검증 요약(terminal-v2 verification_summary, snake_case). 대화 조회·replay가
--    canonical 응답과 함께 돌려준다. summary는 page·claim 수에 비례해 커질 수 있어 event payload(16KB 상한)가 아니라
--    메시지에 둔다. NULL은 빈 배열과 같다(이 열 이전에 직접 만든 완료 메시지 포함).
-- 2. ai_runs.terminal_source_event_id · terminal_source_sha256
--    run을 확정한 Brain terminal의 source_event_id(저장 키, ai_run_events와 같은 128자 규칙)와
--    {type, attempt, fence_token, payload}의 canonical JSON sha256. 재전달이 같은 terminal인지(DUPLICATE),
--    같은 source_event_id의 바뀐 결과인지(CONFLICTING_DUPLICATE) 가른다. event row가 source ID 없이 저장된
--    경우(Activity와 키 충돌)에도 판정할 수 있게 run에 둔다. Server가 만든 실패(deadline 등)는 둘 다 NULL이다.
--    terminal 뒤에는 ai_runs_guard_update가 열 전체를 고정하므로 전환 UPDATE에서만 쓸 수 있다.
--
-- 번호: V3는 1.82가 점유한다. 후속 태스크(1.83~)는 V4부터 쓴다.
-- =====================================================================

ALTER TABLE chat_messages ADD COLUMN verification_summary JSONB;

ALTER TABLE chat_messages ADD CONSTRAINT ck_chat_messages_verification_summary CHECK (
    verification_summary IS NULL
    OR (role = 'ASSISTANT' AND status = 'COMPLETED' AND jsonb_typeof(verification_summary) = 'array')
);

ALTER TABLE ai_runs ADD COLUMN terminal_source_sha256 VARCHAR(64)
    CONSTRAINT ck_ai_runs_terminal_source_sha256 CHECK (terminal_source_sha256 ~ '^[0-9a-f]{64}$');

ALTER TABLE ai_runs ADD COLUMN terminal_source_event_id VARCHAR(128)
    CONSTRAINT ck_ai_runs_terminal_source_event_id CHECK (terminal_source_event_id ~ '^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$');

ALTER TABLE ai_runs ADD CONSTRAINT ck_ai_runs_terminal_source_pair CHECK (
    (terminal_source_event_id IS NULL) = (terminal_source_sha256 IS NULL)
);

ALTER TABLE ai_runs ADD CONSTRAINT ck_ai_runs_terminal_source_status CHECK (
    terminal_source_sha256 IS NULL OR status IN ('COMPLETED', 'FAILED')
);
