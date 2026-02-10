-- credit_balance PK 컬럼 불일치 시 id로 정리
DO $$
BEGIN
    IF to_regclass('public.credit_balance') IS NOT NULL
       AND EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'credit_balance'
              AND column_name = 'balance_id'
       )
       AND NOT EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'credit_balance'
              AND column_name = 'id'
       )
    THEN
        ALTER TABLE public.credit_balance RENAME COLUMN balance_id TO id;

        -- 시퀀스 이름 정리(있을 때만)
        IF to_regclass('public.credit_balance_id_seq') IS NULL
           AND to_regclass('public.credit_balance_balance_id_seq') IS NOT NULL
        THEN
            ALTER SEQUENCE public.credit_balance_balance_id_seq RENAME TO credit_balance_id_seq;
        END IF;

        -- 기본 시퀀스 지정(있을 때만)
        IF to_regclass('public.credit_balance_id_seq') IS NOT NULL THEN
            ALTER TABLE public.credit_balance ALTER COLUMN id SET DEFAULT nextval('public.credit_balance_id_seq');
        END IF;
    END IF;
END $$;
