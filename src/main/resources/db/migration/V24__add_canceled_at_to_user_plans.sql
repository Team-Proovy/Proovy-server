-- 구독 취소 시각 저장 컬럼 추가
ALTER TABLE user_plans
ADD COLUMN IF NOT EXISTS canceled_at TIMESTAMP;

