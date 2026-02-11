-- 만료 전환 배치 조회 최적화: ORDER BY(expired_at, user_plan_id)와 동일한 키 순서 사용
DROP INDEX IF EXISTS idx_user_plans_due_transition;

CREATE INDEX IF NOT EXISTS idx_user_plans_due_transition
    ON user_plans (expired_at, user_plan_id)
    WHERE is_active = true AND canceled_at IS NOT NULL;
