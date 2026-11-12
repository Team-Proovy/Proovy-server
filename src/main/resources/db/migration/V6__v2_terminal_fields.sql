-- =====================================================================
-- V6: Phase 2 확장 terminal 저장 (task 2.15, proovy-brain 2.07 browser-run-fixtures-v1.1·terminal-fixtures-v2.1)
--
-- 저장 위치(2026-11-11 결정): 배지 값은 응답 메시지에, 풀이 내부 값은 기존 verified_solutions JSONB에 둔다.
-- verified_solutions 행은 verified일 때만 생기고 재설명은 새 행을 만들지 않으므로, 배지를 거기 두면 미검증·검증 대상 아님·
-- 재설명 응답의 배지가 남지 않는다. verified_solutions에는 새 열을 만들지 않는다(step_marks·verification_summary 사용).
--
-- 1. chat_messages.response_kind      standard | re_explanation (기본 standard)
-- 2. chat_messages.assurance_scope    verified | unverified | not_applicable — 응답 배지
-- 3. chat_messages.solution_checks    문제별 공개 검산(terminal source 철자, snake_case) 배열. 미검증·검증 대상 아님 응답의
--    단계 검산도 여기서 다시 보여 준다. 문제 20개 × 단계 80개까지 커질 수 있어 event payload(16KB 상한)가 아니라
--    메시지에 둔다(V3 verification_summary와 같은 이유). 대화 조회와 SSE replay가 같은 값으로 browser 철자를 만든다.
--
-- v2 이전 행(이 migration 전 완료 메시지, 확장 필드 없는 terminal)은 response_kind = 'standard', 나머지는 NULL이다.
-- 필수 제약은 확장 terminal로 저장하는 행(assurance_scope IS NOT NULL)에만 건다: 세 값이 함께 있고 완료 assistant
-- 메시지이며, 재설명은 not_applicable·검산 없음이고, 응답 배지는 문제별 배지에서 not_applicable을 뺀 집계와 같다
-- (사용자 결정 2026-11-12). terminal 뒤 불변은 V1 chat_messages_guard_update가 행 전체로 강제한다.
-- =====================================================================

ALTER TABLE chat_messages
    ADD COLUMN response_kind VARCHAR(20) NOT NULL DEFAULT 'standard'
        CONSTRAINT ck_chat_messages_response_kind CHECK (response_kind IN ('standard', 're_explanation')),
    ADD COLUMN assurance_scope VARCHAR(20)
        CONSTRAINT ck_chat_messages_assurance_scope
            CHECK (assurance_scope IN ('verified', 'unverified', 'not_applicable')),
    ADD COLUMN solution_checks JSONB;

ALTER TABLE chat_messages ADD CONSTRAINT ck_chat_messages_v2_terminal CHECK (
    CASE WHEN assurance_scope IS NULL THEN
        response_kind = 'standard' AND solution_checks IS NULL
    ELSE
        role = 'ASSISTANT' AND status = 'COMPLETED'
        AND solution_checks IS NOT NULL
        AND jsonb_typeof(solution_checks) = 'array'
        AND jsonb_array_length(solution_checks) <= 20
        AND (response_kind = 'standard'
             OR (assurance_scope = 'not_applicable' AND jsonb_array_length(solution_checks) = 0))
        AND assurance_scope = CASE
            WHEN jsonb_path_exists(solution_checks, '$[*] ? (@.assurance_scope == "unverified")') THEN 'unverified'
            WHEN jsonb_path_exists(solution_checks, '$[*] ? (@.assurance_scope == "verified")') THEN 'verified'
            ELSE 'not_applicable'
        END
    END
);
