package com.proovy.support.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * 실제 Postgres 위에 테스트마다 새 database를 만들어 주는 격리 지원 도구.
 *
 * <p>관리자(superuser) 접속 정보는 환경변수로 받는다. 고정 compose 자원(proovy-postgres:15432)을
 * 공유하지 않도록 기본값을 두지 않는다.
 * <ul>
 *   <li>{@code PROOVY_TEST_PG_URL} — 예: {@code jdbc:postgresql://127.0.0.1:21432/postgres}</li>
 *   <li>{@code PROOVY_TEST_PG_USER}, {@code PROOVY_TEST_PG_PASSWORD}</li>
 *   <li>{@code PROOVY_REQUIRE_DB_TESTS=true} — 미구성 시 skip이 아니라 실패 (CI)</li>
 * </ul>
 */
public final class PostgresTestDatabase implements AutoCloseable {

    private static final String ADMIN_URL = System.getenv("PROOVY_TEST_PG_URL");
    private static final String ADMIN_USER = System.getenv("PROOVY_TEST_PG_USER");
    private static final String ADMIN_PASSWORD = System.getenv("PROOVY_TEST_PG_PASSWORD");
    private static final boolean REQUIRED = Boolean.parseBoolean(System.getenv("PROOVY_REQUIRE_DB_TESTS"));

    private final String databaseName;

    private PostgresTestDatabase(String databaseName) {
        this.databaseName = databaseName;
    }

    /**
     * 빈 database를 만든다. 환경이 없으면 skip(로컬) 또는 실패(CI).
     */
    public static PostgresTestDatabase createEmpty() {
        requireConfigured();
        String name = "proovy_t_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection admin = DriverManager.getConnection(ADMIN_URL, ADMIN_USER, ADMIN_PASSWORD);
             Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to create isolated test database", e);
        }
        return new PostgresTestDatabase(name);
    }

    /**
     * 빈 database + 제품 baseline migration.
     */
    public static PostgresTestDatabase createMigrated() {
        PostgresTestDatabase db = createEmpty();
        db.migrate(ADMIN_USER, ADMIN_PASSWORD);
        return db;
    }

    public static void requireConfigured() {
        boolean configured = ADMIN_URL != null && ADMIN_USER != null && ADMIN_PASSWORD != null;
        if (!configured && REQUIRED) {
            throw new IllegalStateException(
                    "PROOVY_REQUIRE_DB_TESTS=true but PROOVY_TEST_PG_URL/USER/PASSWORD are not set");
        }
        Assumptions.assumeTrue(configured,
                "Postgres DB tests are not configured (PROOVY_TEST_PG_URL/USER/PASSWORD) — NOT EXECUTED");
    }

    public Flyway flyway(String user, String password) {
        return Flyway.configure()
                .dataSource(jdbcUrl(), user, password)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .load();
    }

    public void migrate(String user, String password) {
        flyway(user, password).migrate();
    }

    public String name() {
        return databaseName;
    }

    public String jdbcUrl() {
        int slash = ADMIN_URL.lastIndexOf('/');
        int query = ADMIN_URL.indexOf('?', slash);
        String suffix = query >= 0 ? ADMIN_URL.substring(query) : "";
        return ADMIN_URL.substring(0, slash + 1) + databaseName + suffix;
    }

    public static String adminUser() {
        return ADMIN_USER;
    }

    public static String adminPassword() {
        return ADMIN_PASSWORD;
    }

    public Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), ADMIN_USER, ADMIN_PASSWORD);
    }

    public Connection connect(String user, String password) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), user, password);
    }

    /** classpath SQL 스크립트를 관리자 권한으로 실행한다. */
    public void runScript(String classpathResource) {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute(readResource(classpathResource));
        } catch (SQLException e) {
            throw new IllegalStateException("failed to run " + classpathResource, e);
        }
    }

    public static String readResource(String classpathResource) {
        try (InputStream in = PostgresTestDatabase.class.getClassLoader().getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalArgumentException("missing classpath resource " + classpathResource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 관리자 database 연결에서 실행한다 (role 생성 등 cluster 단위 작업). */
    public static void executeAsAdminOnServer(String sql) {
        try (Connection admin = DriverManager.getConnection(ADMIN_URL, ADMIN_USER, ADMIN_PASSWORD);
             Statement st = admin.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("admin statement failed", e);
        }
    }

    @Override
    public void close() {
        try (Connection admin = DriverManager.getConnection(ADMIN_URL, ADMIN_USER, ADMIN_PASSWORD);
             Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + databaseName + " WITH (FORCE)");
        } catch (SQLException e) {
            throw new IllegalStateException("failed to drop isolated test database " + databaseName, e);
        }
    }
}
