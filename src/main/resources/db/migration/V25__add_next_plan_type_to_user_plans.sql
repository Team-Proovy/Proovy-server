-- 예약 다운그레이드(만료 시 플랜 전환) 타깃 플랜 저장 컬럼 추가
ALTER TABLE user_plans
ADD COLUMN IF NOT EXISTS next_plan_type VARCHAR(10);

-- 기존 취소 데이터는 기본적으로 FREE 전환으로 간주
UPDATE user_plans
SET next_plan_type = 'FREE'
WHERE canceled_at IS NOT NULL
  AND next_plan_type IS NULL;

-- 만료된 예약 전환 대상 조회 최적화
CREATE INDEX IF NOT EXISTS idx_user_plans_due_transition
    ON user_plans (expired_at)
    WHERE is_active = true AND canceled_at IS NOT NULL;
