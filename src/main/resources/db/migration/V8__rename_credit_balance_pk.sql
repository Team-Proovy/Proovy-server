-- credit_balance PK 컬럼 불일치 시 id로 정리
-- PostgreSQL 18 호환: IDENTITY 컬럼에는 DEFAULT 설정 불가
DO $$
DECLARE
v_is_identity BOOLEAN := FALSE;
BEGIN
    IF to_regclass('public.credit_balance') IS NOT NULL
       AND EXISTS (
            SELECT 1
            FROM information_schema.columns c
            WHERE c.table_schema = 'public'
              AND c.table_name = 'credit_balance'
              AND c.column_name = 'balance_id'
       )
       AND NOT EXISTS (
            SELECT 1
            FROM information_schema.columns c
            WHERE c.table_schema = 'public'
              AND c.table_name = 'credit_balance'
              AND c.column_name = 'id'
       )
    THEN
ALTER TABLE public.credit_balance RENAME COLUMN balance_id TO id;

-- 시퀀스 이름 정리(있을 때만)
IF to_regclass('public.credit_balance_id_seq') IS NULL
           AND to_regclass('public.credit_balance_balance_id_seq') IS NOT NULL
        THEN
ALTER SEQUENCE public.credit_balance_balance_id_seq RENAME TO credit_balance_id_seq;
END IF;

        -- IDENTITY 컬럼 여부 확인 (PostgreSQL 10+)
SELECT EXISTS (
    SELECT 1
    FROM information_schema.columns c
    WHERE c.table_schema = 'public'
      AND c.table_name = 'credit_balance'
      AND c.column_name = 'id'
      AND c.is_identity = 'YES'
) INTO v_is_identity;

-- IDENTITY 컬럼이 아닐 때만 DEFAULT 설정
IF NOT v_is_identity
           AND to_regclass('public.credit_balance_id_seq') IS NOT NULL
        THEN
ALTER TABLE public.credit_balance
    ALTER COLUMN id SET DEFAULT nextval('public.credit_balance_id_seq');
END IF;
END IF;
END $$;
