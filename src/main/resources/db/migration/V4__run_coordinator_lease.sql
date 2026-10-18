-- =====================================================================
-- 단일 Server run coordinator lease (task 1.83, docs/plan/09)
-- 다중 Server 인스턴스 중 lease를 가진 하나만 PENDING run을 claim하고 전역 실행 슬롯을 배정한다.
-- lease가 없는 인스턴스는 claim하지 않는다(다중 coordinator는 S14 검증 전 활성화하지 않는다).
-- =====================================================================

CREATE TABLE run_coordinator_leases (
    coordinator VARCHAR(32)  PRIMARY KEY
        CONSTRAINT ck_run_coordinator_leases_name CHECK (coordinator ~ '^[a-z][a-z0-9-]{0,31}$'),
    owner       VARCHAR(128) NOT NULL
        CONSTRAINT ck_run_coordinator_leases_owner CHECK (owner ~ '^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$'),
    -- 소유자가 바뀔 때마다 1씩 오른다(관측용).
    epoch       BIGINT       NOT NULL CONSTRAINT ck_run_coordinator_leases_epoch CHECK (epoch >= 1),
    acquired_at TIMESTAMPTZ  NOT NULL,
    renewed_at  TIMESTAMPTZ  NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_run_coordinator_leases_times CHECK (acquired_at <= renewed_at AND renewed_at < expires_at)
);
