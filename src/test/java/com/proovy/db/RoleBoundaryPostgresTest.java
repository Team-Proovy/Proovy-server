package com.proovy.db;

import com.proovy.support.db.PostgresTestDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static com.proovy.support.db.ProductRows.count;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Server와 Brain DB role의 schema 접근 경계 (docs/plan/07).
 *
 * <p>격리 Postgres에 role을 만들고, Server Flyway를 proovy_server로 실행한 뒤 Brain schema를
 * brain_migration으로 만들어 Brain 0001 권한을 재현하고 provisioning 스크립트를 적용한다.
 * 외부 운영 DB 권한은 변경하지 않는다.
 */
@Tag("postgres")
class RoleBoundaryPostgresTest {

    private static final String PERMISSION_DENIED = "42501";
    private static final String PASSWORD = "role-boundary-test";

    private static PostgresTestDatabase db;

    @BeforeAll
    static void provision() throws SQLException {
        PostgresTestDatabase.requireConfigured();
        for (String role : new String[]{"proovy_server", "brain_migration", "brain_runtime"}) {
            PostgresTestDatabase.executeAsAdminOnServer("""
                    DO $$ BEGIN
                        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%1$s') THEN
                            CREATE ROLE %1$s LOGIN PASSWORD '%2$s';
                        ELSE
                            ALTER ROLE %1$s LOGIN PASSWORD '%2$s';
                        END IF;
                    END $$;
                    """.formatted(role, PASSWORD));
        }
        db = PostgresTestDatabase.createEmpty();
        try (Connection admin = db.connect(); Statement st = admin.createStatement()) {
            // pg_trgm은 superuser가 미리 설치하고 Server role은 public schema 소유 작업만 한다.
            st.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            st.execute("GRANT USAGE, CREATE ON SCHEMA public TO proovy_server");
            // Brain migration이 만드는 schema를 재현한다 (proovy-brain 0001_brain_schema_permissions.sql)
            st.execute("CREATE SCHEMA brain_runtime AUTHORIZATION brain_migration");
            st.execute("CREATE SCHEMA brain_retrieval AUTHORIZATION brain_migration");
        }
        db.migrate("proovy_server", PASSWORD);
        try (Connection brain = db.connect("brain_migration", PASSWORD); Statement st = brain.createStatement()) {
            st.execute("CREATE TABLE brain_runtime.run_records (run_id TEXT PRIMARY KEY)");
            st.execute("CREATE TABLE brain_retrieval.chunks (chunk_id TEXT PRIMARY KEY)");
            st.execute("REVOKE ALL ON SCHEMA brain_runtime FROM PUBLIC");
            st.execute("REVOKE ALL ON SCHEMA brain_retrieval FROM PUBLIC");
            st.execute("GRANT USAGE ON SCHEMA brain_runtime, brain_retrieval TO brain_runtime");
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA brain_runtime TO brain_runtime");
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA brain_retrieval TO brain_runtime");
        }
        db.runScript("db/provisioning/role-boundary.sql");
        // 재적용해도 같은 결과다
        db.runScript("db/provisioning/role-boundary.sql");
    }

    @AfterAll
    static void drop() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void brainRolesCannotReadOrWriteProductTables() throws SQLException {
        for (String role : new String[]{"brain_runtime", "brain_migration"}) {
            try (Connection brain = db.connect(role, PASSWORD); Statement st = brain.createStatement()) {
                assertDenied(st, "SELECT count(*) FROM public.notes");
                assertDenied(st, "SELECT count(*) FROM public.ai_runs");
                assertDenied(st, "INSERT INTO public.ai_run_events (run_id, note_id, seq, event_type) "
                        + "VALUES (gen_random_uuid(), 1, 1, 'run.created')");
                assertDenied(st, "UPDATE public.chat_messages SET plain_text = 'x'");
                assertDenied(st, "CREATE TABLE public.brain_shadow (id INT)");
            }
        }
        // Brain 자체 schema는 계속 사용할 수 있다
        try (Connection brain = db.connect("brain_runtime", PASSWORD); Statement st = brain.createStatement()) {
            st.execute("INSERT INTO brain_runtime.run_records VALUES ('run-1')");
            st.execute("SELECT count(*) FROM brain_retrieval.chunks");
        }
    }

    @Test
    void serverRoleCannotAccessBrainSchemas() throws SQLException {
        try (Connection server = db.connect("proovy_server", PASSWORD); Statement st = server.createStatement()) {
            assertDenied(st, "SELECT count(*) FROM brain_runtime.run_records");
            assertDenied(st, "SELECT count(*) FROM brain_retrieval.chunks");
            assertDenied(st, "CREATE TABLE brain_runtime.server_shadow (id INT)");
            // 제품 schema는 소유자로서 정상 사용한다
            st.execute("INSERT INTO users (nickname) VALUES ('server-role')");
            assertThat(count(server, "SELECT count(*) FROM users")).isEqualTo(1);
        }
    }

    private static void assertDenied(Statement st, String sql) {
        assertThatThrownBy(() -> st.execute(sql))
                .as(sql)
                .isInstanceOfSatisfying(SQLException.class,
                        e -> assertThat(e.getSQLState()).isEqualTo(PERMISSION_DENIED));
    }
}
