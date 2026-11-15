-- =====================================================================
-- V7: VerifiedSolution 명시적 최종 답 메타데이터 (task 2.13)
--
-- 정정(follow-up CORRECTION) 요청 시 Brain이 기존 풀이와 새 검증 답을 비교할 수
-- 있도록 terminal verified_solutions artifact의 answer_claim_id · answer_text를
-- 저장하고 internal-run-v1 VerifiedSolutionRef로만 재전달한다.
-- 공개 브라우저 응답·chat_messages·browser-run-v1에는 절대 노출하지 않는다.
-- 이전 행은 NULL 쌍으로 남고 Brain은 보수적으로 clarification(missing_input) 처리한다.
-- =====================================================================

ALTER TABLE verified_solutions
    ADD COLUMN answer_claim_id VARCHAR(128),
    ADD COLUMN answer_text TEXT,
    ADD CONSTRAINT ck_verified_solutions_answer_pair CHECK (
        (answer_claim_id IS NULL) = (answer_text IS NULL)
    ),
    ADD CONSTRAINT ck_verified_solutions_answer_claim_id CHECK (
        answer_claim_id IS NULL OR answer_claim_id ~ '^claim-(?:[1-9][0-9]*|[0-9a-f]{20}-[0-9]{3})$'
    ),
    ADD CONSTRAINT ck_verified_solutions_answer_text CHECK (
        answer_text IS NULL OR (char_length(answer_text) BETWEEN 1 AND 2000 AND btrim(answer_text) <> '')
    );
