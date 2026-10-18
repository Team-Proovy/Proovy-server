package com.proovy.domain.run.cancel;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proovy.domain.run.admission.RunAdmission;
import com.proovy.domain.run.admission.RunAdmissionService;
import com.proovy.domain.run.cancel.CancelFakeBrain.CancelMode;
import com.proovy.domain.run.coordinator.RunClaimStore;
import com.proovy.domain.run.coordinator.RunCoordinator;
import com.proovy.support.db.PostgresTestDatabase;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 취소(1.84)와 실제 coordinator(1.83) worker의 end-to-end: 실제 {@code BrainRunClient} + loopback 가짜 Brain + 실제 Postgres.
 * 전역 슬롯 1이라 CANCELING run이 Brain 정리 확인 전까지 다음 run의 dispatch를 막는지(슬롯 유지) 본다.
 */
@Tag("postgres")
// 끝난 class의 context를 닫아 finalizer sweep이 닫힌 DB를 계속 두드리지 않게 한다
@DirtiesContext
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "gcs.project-id=proovy-test",
        "gcs.endpoint=http://127.0.0.1:1",
        "jwt.secret=test-jwt-secret-test-jwt-secret-0123456789",
        "oauth.kakao.client-id=test", "oauth.kakao.client-secret=test",
        "oauth.naver.client-id=test", "oauth.naver.client-secret=test",
        "openrouter.api.key=test",
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1",
        "spring.task.scheduling.pool.size=1",
        "proovy.subscription.plan-transition-fixed-delay-ms=3600000",
        "proovy.subscription.auto-renew-fixed-delay-ms=3600000",
        "proovy.brain.auth.mode=shared-token",
        "proovy.brain.auth.shared-token=t184-shared-token",
        "proovy.brain.stream-idle-timeout=3s",
        "proovy.run.coordinator.enabled=true",
        "proovy.run.coordinator.instance-id=t184-coordinator",
        "proovy.run.coordinator.max-concurrent-runs=1",
        "proovy.run.coordinator.poll-interval=100ms",
        "proovy.run.coordinator.heartbeat-interval=300ms",
        "proovy.run.coordinator.lease-ttl=2s",
        "proovy.run.coordinator.deadline-sweep-interval=200ms",
        "proovy.run.coordinator.min-dispatch-remaining=0s",
        "proovy.run.coordinator.redelivery.initial-backoff=50ms",
        "proovy.run.coordinator.redelivery.max-backoff=200ms",
        "proovy.run.coordinator.shutdown-timeout=3s",
        "proovy.run.cancel.sweep-interval=200ms",
        "proovy.run.cancel.cleanup-timeout=60s"
})
class RunCancelCoordinatorPostgresTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    private static PostgresTestDatabase db;
    private static CancelFakeBrain brain;

    @BeforeAll
    static void setUp() {
        db = PostgresTestDatabase.createMigrated();
        db.runScript("db/seed/product-seed.sql");
        brain = new CancelFakeBrain();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> db.jdbcUrl());
        registry.add("spring.datasource.username", PostgresTestDatabase::adminUser);
        registry.add("spring.datasource.password", PostgresTestDatabase::adminPassword);
        registry.add("proovy.brain.base-url", () -> brain.baseUrl());
    }

    @AfterAll
    static void tearDown() {
        if (brain != null) {
            brain.close();
        }
        if (db != null) {
            db.close();
        }
    }

    @Autowired RunAdmissionService admission;
    @Autowired RunCancelService cancels;
    @Autowired RunCoordinator coordinator;
    @Autowired RunClaimStore claims;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @Test
    @DisplayName("실행 중 취소: worker는 wind-down Activity를 저장하고 OBSOLETE로 끝나지만 슬롯은 CANCELED 확정까지 유지, 확정 뒤 다음 run dispatch")
    void cancelingRunKeepsTheGlobalSlotUntilBrainCleanupIsConfirmed() {
        long first = newUser();
        RunAdmission running = admit(first);
        String runId = running.runId().toString();
        brain.mode(runId, CancelMode.NEVER_CONFIRM);
        await(() -> eventTypes(running.runId()).contains("activity.started"));
        assertThat(coordinator.isExecuting(running.runId())).isTrue();

        assertThat(cancels.cancel(first, runId, null).outcome().wireValue()).isEqualTo("accepted");
        // Brain cancel → stream이 열린 Activity의 canceled 표식을 보내고 terminal 없이 닫힌다 → worker는 status(canceling)로 OBSOLETE
        await(() -> eventTypes(running.runId()).contains("activity.canceled"));
        await(() -> !coordinator.isExecuting(running.runId()));
        assertThat(status(running.runId())).isEqualTo("CANCELING");

        // 정리 확인 전: 전역 슬롯(1)을 계속 차지해 다른 사용자의 run이 dispatch되지 않는다
        RunAdmission waiting = admit(newUser());
        sleep(1500);
        assertThat(claims.countActiveExecutions()).isEqualTo(1);
        assertThat(status(waiting.runId())).isEqualTo("PENDING");
        assertThat(brain.streamCalls(waiting.runId().toString())).isZero();

        // Brain 정리 완료 → CANCELED 확정 → 슬롯 반환 → 대기 run dispatch
        brain.mode(runId, CancelMode.CONFIRM);
        await(() -> "CANCELED".equals(status(running.runId())));
        assertThat(eventTypes(running.runId())).containsExactly("run.created", "activity.started", "run.canceling",
                "activity.canceled", "run.canceled");
        assertThat(jdbc.queryForObject("SELECT runtime_cleanup_at IS NOT NULL FROM ai_runs WHERE run_id = ?",
                Boolean.class, running.runId())).isTrue();
        await(() -> brain.streamCalls(waiting.runId().toString()) == 1);
        brain.complete(waiting.runId().toString());
        await(() -> "COMPLETED".equals(status(waiting.runId())));

        // 취소된 run은 다시 전달되지 않았다(자동 재실행 없음)
        assertThat(brain.streamCalls(runId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempt FROM ai_runs WHERE run_id = ?", Integer.class,
                running.runId())).isEqualTo(1);
    }

    // ------------------------------------------------------------------

    private RunAdmission admit(long user) {
        ObjectNode body = mapper.createObjectNode();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("text", "x^2-5x+6=0을 풀어줘 " + UUID.randomUUID());
        return admission.admit(user, body);
    }

    private long newUser() {
        String key = UUID.randomUUID().toString();
        long user = jdbc.queryForObject("""
                INSERT INTO users (nickname, provider, provider_user_id, email, created_at, updated_at)
                VALUES ('t184', 'KAKAO', ?, ?, now(), now()) RETURNING user_id
                """, Long.class, key, key + "@example.test");
        jdbc.update("""
                INSERT INTO credit_balance (user_id, daily_free_credit, daily_free_limit, daily_expires_at, free_credit,
                                            paid_credit, created_at, updated_at)
                VALUES (?, 100, 100, now() + interval '1 day', 0, 0, now(), now())
                """, user);
        return user;
    }

    private String status(UUID runId) {
        return jdbc.queryForObject("SELECT status FROM ai_runs WHERE run_id = ?", String.class, runId);
    }

    private List<String> eventTypes(UUID runId) {
        return jdbc.queryForList("SELECT event_type FROM ai_run_events WHERE run_id = ? ORDER BY seq", String.class,
                runId);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void await(BooleanSupplier condition) {
        Instant until = Instant.now().plus(WAIT);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(until)) {
                throw new AssertionError("condition not met within " + WAIT);
            }
            sleep(50);
        }
    }
}
