-- V20: PostgreSQL 18(IDENTITY) 환경에서 credit_balance.id DEFAULT 설정 분기 처리
-- 목적:
-- 1) id가 IDENTITY면 DEFAULT 설정하면 안 됨
-- 2) id가 IDENTITY가 아니고 시퀀스가 있으면 DEFAULT nextval 설정

DO $$
DECLARE
v_is_identity BOOLEAN := FALSE;
BEGIN
    -- 테이블/컬럼 존재 확인 (id가 이미 있는 상태에서만)
    IF to_regclass('public.credit_balance') IS NOT NULL
       AND EXISTS (
            SELECT 1
            FROM information_schema.columns c
            WHERE c.table_schema = 'public'
              AND c.table_name = 'credit_balance'
              AND c.column_name = 'id'
       )
    THEN
        -- 시퀀스 이름 정리(있을 때만)
        IF to_regclass('public.credit_balance_id_seq') IS NULL
           AND to_regclass('public.credit_balance_balance_id_seq') IS NOT NULL
        THEN
ALTER SEQUENCE public.credit_balance_balance_id_seq RENAME TO credit_balance_id_seq;
END IF;

        -- IDENTITY 컬럼 여부 확인
SELECT EXISTS (
    SELECT 1
    FROM information_schema.columns c
    WHERE c.table_schema = 'public'
      AND c.table_name = 'credit_balance'
      AND c.column_name = 'id'
      AND c.is_identity = 'YES'
)
INTO v_is_identity;

-- IDENTITY가 아닐 때만 DEFAULT 설정
IF NOT v_is_identity
           AND to_regclass('public.credit_balance_id_seq') IS NOT NULL
        THEN
ALTER TABLE public.credit_balance
    ALTER COLUMN id SET DEFAULT nextval('public.credit_balance_id_seq');
END IF;
END IF;
END $$;
