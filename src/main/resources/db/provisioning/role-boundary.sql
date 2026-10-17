-- =====================================================================
-- Server ↔ Brain DB role 경계 (docs/plan/07: 하나의 DB에서 public 제품 schema와
-- brain_runtime·brain_retrieval schema를 role로 격리)
--
-- Flyway가 실행하지 않는다. 운영자가 DB 관리자 권한으로 role 생성·Server Flyway
-- 최초 실행 전후 한 번 적용한다(재실행해도 같은 결과). 실제 운영 DB 적용은 S14
-- 인수 작업에서 별도 승인 후 수행한다.
--
-- 전제 role
--   proovy_server   : Server 런타임 + Flyway 실행 role, public 제품 테이블 소유자
--   brain_migration : Brain schema 소유/migration role (proovy-brain 0001 참고)
--   brain_runtime   : Brain 런타임 role
-- 결과
--   - brain_* role은 public schema 자체를 사용할 수 없어 제품 테이블을 읽거나 쓰지 못한다.
--   - proovy_server는 brain_runtime·brain_retrieval schema를 사용할 수 없다.
-- =====================================================================

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'proovy_server') THEN
        RAISE EXCEPTION 'role proovy_server must exist before applying the role boundary';
    END IF;
END $$;

-- 제품 schema: PUBLIC 기본 USAGE를 걷어내고 Server role에만 부여한다.
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO proovy_server;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE proovy_server IN SCHEMA public REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE proovy_server IN SCHEMA public REVOKE ALL ON SEQUENCES FROM PUBLIC;

DO $$
DECLARE
    brain_role TEXT;
    brain_schema TEXT;
BEGIN
    FOREACH brain_role IN ARRAY ARRAY['brain_migration', 'brain_runtime'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = brain_role) THEN
            EXECUTE format('REVOKE ALL ON SCHEMA public FROM %I', brain_role);
            EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA public FROM %I', brain_role);
            EXECUTE format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM %I', brain_role);
        END IF;
    END LOOP;

    -- Brain schema: Server role의 접근을 제거한다 (PUBLIC 권한은 Brain 0001 migration이 이미 제거).
    FOREACH brain_schema IN ARRAY ARRAY['brain_runtime', 'brain_retrieval'] LOOP
        IF EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = brain_schema) THEN
            EXECUTE format('REVOKE ALL ON SCHEMA %I FROM PUBLIC, proovy_server', brain_schema);
            EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA %I FROM PUBLIC, proovy_server', brain_schema);
            EXECUTE format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA %I FROM PUBLIC, proovy_server', brain_schema);
        END IF;
    END LOOP;
END $$;
