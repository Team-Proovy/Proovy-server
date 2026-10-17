package com.proovy.db;

import com.proovy.support.db.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;

import static com.proovy.support.db.ProductRows.count;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 빈 DB에서 baseline·seed만으로 제품 schema가 재현되고, 과거 schema가 있는 DB에는
 * baseline하지 않고 실패하는지 검사한다.
 */
@Tag("postgres")
class ProductBaselineMigrationPostgresTest {

    @Test
    void emptyDatabaseMigratesToBaselineAndSeedIsReproducible() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.createEmpty()) {
            Flyway flyway = db.flyway(PostgresTestDatabase.adminUser(), PostgresTestDatabase.adminPassword());
            assertThat(flyway.migrate().migrationsExecuted).isGreaterThanOrEqualTo(1);

            MigrationInfo[] applied = flyway.info().applied();
            assertThat(applied[0].getVersion().getVersion()).isEqualTo("1");
            assertThat(applied[0].getDescription()).isEqualTo("product baseline");
            // 재실행은 no-op
            assertThat(flyway.migrate().migrationsExecuted).isZero();

            db.runScript("db/seed/product-seed.sql");
            try (Connection c = db.connect()) {
                assertThat(count(c, "SELECT count(*) FROM users")).isEqualTo(2);
                assertThat(count(c, "SELECT count(*) FROM notes")).isEqualTo(3);
                assertThat(count(c, "SELECT count(*) FROM chat_messages")).isZero();
                assertThat(count(c, "SELECT count(*) FROM ai_runs")).isZero();
            }
        }
    }

    @Test
    void databaseWithLegacySchemaIsNotBaselined() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.createEmpty()) {
            try (Connection c = db.connect(); Statement st = c.createStatement()) {
                st.execute("CREATE TABLE chat_sessions (chat_session_id BIGSERIAL PRIMARY KEY)");
            }
            Flyway flyway = db.flyway(PostgresTestDatabase.adminUser(), PostgresTestDatabase.adminPassword());
            assertThatThrownBy(flyway::migrate)
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("non-empty schema");
        }
    }

    @Test
    void databaseWithLegacyMigrationHistoryFailsValidation() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.createEmpty()) {
            Flyway flyway = db.flyway(PostgresTestDatabase.adminUser(), PostgresTestDatabase.adminPassword());
            flyway.migrate();
            // 과거 서비스 DB history를 흉내 낸다: 같은 버전 1이 다른 스크립트(create credit balance)로 기록돼 있다
            try (Connection c = db.connect(); Statement st = c.createStatement()) {
                st.execute("""
                        UPDATE flyway_schema_history
                        SET description = 'create credit balance', script = 'V1__create_credit_balance.sql', checksum = 12345
                        WHERE version = '1'
                        """);
            }
            assertThatThrownBy(flyway::migrate)
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("checksum mismatch");
        }
    }
}
