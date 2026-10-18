-- =====================================================================
-- stale 실행 회수 횟수 (task 1.85, docs/plan/05·11, ADR 0008)
-- lease가 만료된 RUNNING|RETRYING run을 같은 attempt·fence로 인수할 때마다 1씩 오른다(단조).
-- 재시작·재인수가 회수 예산을 초기화하지 않게 run 행에 누적한다. 한도를 넘으면 저장 terminal 확인 한 번 뒤 실패 수습한다.
-- =====================================================================

ALTER TABLE ai_runs
    ADD COLUMN recovery_count INTEGER NOT NULL DEFAULT 0
        CONSTRAINT ck_ai_runs_recovery_count CHECK (recovery_count >= 0);

-- V1 ai_runs_guard_update의 단조 카운터 규칙을 이 열에도 적용한다(V1 함수는 바꾸지 않는다).
-- terminal 뒤 불변은 V1 guard가 이미 행 전체로 강제한다.
CREATE FUNCTION ai_runs_guard_recovery_count() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.recovery_count < OLD.recovery_count THEN
        RAISE EXCEPTION 'ai_runs recovery_count is monotonic'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_ai_runs_guard_recovery_count
    BEFORE UPDATE OF recovery_count ON ai_runs
    FOR EACH ROW EXECUTE FUNCTION ai_runs_guard_recovery_count();
