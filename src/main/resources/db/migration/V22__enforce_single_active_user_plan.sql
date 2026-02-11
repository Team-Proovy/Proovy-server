-- 사용자별 활성 플랜은 최대 1개만 허용한다.
-- 1) 기존 데이터 중복 정리
-- 2) 부분 유니크 인덱스로 정합성 강제

UPDATE user_plans
SET is_active = false
WHERE is_active IS NULL;

DO $$
DECLARE
    batch_size INT := 5000;
    updated_count INT;
BEGIN
    LOOP
        WITH duplicate_active AS (
            SELECT user_plan_id
            FROM (
                SELECT
                    user_plan_id,
                    ROW_NUMBER() OVER (
                        PARTITION BY user_id
                        ORDER BY
                            CASE WHEN started_at IS NULL THEN 1 ELSE 0 END,
                            started_at DESC,
                            user_plan_id DESC
                    ) AS rn
                FROM user_plans
                WHERE is_active = true
            ) ranked
            WHERE rn > 1
            LIMIT batch_size
        )
        UPDATE user_plans up
        SET is_active = false
        WHERE up.user_plan_id IN (SELECT user_plan_id FROM duplicate_active);

        GET DIAGNOSTICS updated_count = ROW_COUNT;
        EXIT WHEN updated_count = 0;

        -- 대량 데이터에서 장시간 락 점유를 줄이기 위해 짧게 양보
        PERFORM pg_sleep(0.05);
    END LOOP;
END $$;

ALTER TABLE user_plans
    ALTER COLUMN is_active SET NOT NULL;

ALTER TABLE user_plans
    ALTER COLUMN is_active SET DEFAULT true;

CREATE UNIQUE INDEX IF NOT EXISTS ux_user_plans_active_user
    ON user_plans (user_id)
    WHERE is_active = true;
