-- =====================================================================
-- 브라우저 requestId binding (task 1.79, #220)
-- =====================================================================
-- 같은 requestId 재전송이 "같은 사용자·같은 의미"인지 판정하는 browser-run-v1 요청 fingerprint를 접수 시 고정한다.
-- ai_runs.request_fingerprint는 internal-run-v1 fingerprint(history·corpus·정책 포함)라 재전송 body만으로 다시 계산할 수 없다.
-- 접수 transaction이 run·차감과 함께 insert하고 이후 바꾸지 않는다. run 삭제(Note·회원 삭제)와 함께 지워지며,
-- 그 뒤 같은 requestId 재사용은 남아 있는 request_usage_charges(PK = request_id)가 거부한다.

CREATE TABLE run_request_bindings (
    request_id            VARCHAR(128) PRIMARY KEY
        CONSTRAINT ck_run_request_bindings_request_id CHECK (request_id ~ '^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$'),
    user_id               BIGINT       NOT NULL,
    run_id                UUID         NOT NULL CONSTRAINT uq_run_request_bindings_run UNIQUE,
    fingerprint_algorithm VARCHAR(64)  NOT NULL
        CONSTRAINT ck_run_request_bindings_algorithm CHECK (fingerprint_algorithm ~ '^[a-z0-9-]{1,64}$'),
    request_fingerprint   VARCHAR(64)  NOT NULL
        CONSTRAINT ck_run_request_bindings_fingerprint CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- binding은 같은 request·사용자의 run만 가리킨다.
    CONSTRAINT fk_run_request_bindings_run FOREIGN KEY (run_id, request_id, user_id)
        REFERENCES ai_runs (run_id, request_id, user_id) ON DELETE CASCADE
);

CREATE TRIGGER trg_run_request_bindings_immutable
    BEFORE UPDATE ON run_request_bindings
    FOR EACH ROW EXECUTE FUNCTION proovy_reject_update();

CREATE TRIGGER trg_run_request_bindings_no_direct_delete
    BEFORE DELETE ON run_request_bindings
    FOR EACH ROW EXECUTE FUNCTION proovy_reject_direct_delete();
