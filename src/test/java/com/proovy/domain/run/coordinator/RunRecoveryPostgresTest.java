package com.proovy.domain.run.coordinator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proovy.domain.run.admission.RunAdmission;
import com.proovy.domain.run.admission.RunAdmissionService;
import com.proovy.domain.run.coordinator.FakeBrain.Delivery;
import com.proovy.domain.run.coordinator.FakeBrain.StatusMode;
import com.proovy.domain.run.coordinator.FakeBrain.Step;
import com.proovy.domain.run.coordinator.RunClaimStore.StaleRun;
import com.proovy.domain.run.entity.RunFailureCode;
import com.proovy.domain.run.terminal.RunTerminalService;
import com.proovy.domain.usage.service.RequestUsageService;
import com.proovy.support.db.PostgresTestDatabase;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * stale 실행 회수(task 1.85)를 실제 Postgres + 실제 {@code BrainRunClient} + loopback 가짜 Brain으로 검사한다.
 * Server 재시작은 coordinator {@code stop()}(진행 중 run은 lease만 만료된 채 남는다) → Brain 상태 주입 → {@code start()}로,
 * 응답 유실은 가짜 Brain의 status 500으로, 이전 프로세스·다른 인스턴스는 다른 owner의 claim·lease로 주입한다.
 * 검사: 저장 결과 회수에 새 stream이 없다, 실행 소실에 재전송이 없다, terminal·보상이 한 번이다, attempt·fence가 그대로다.
 */
@Tag("postgres")
@ActiveProfiles("test")
@DirtiesContext
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
        "proovy.brain.auth.shared-token=t185-shared-token",
        "proovy.brain.stream-idle-timeout=3s",
        "proovy.run.coordinator.enabled=true",
        "proovy.run.coordinator.instance-id=t185-coordinator",
        "proovy.run.coordinator.max-concurrent-runs=2",
        "proovy.run.coordinator.poll-interval=100ms",
        "proovy.run.coordinator.heartbeat-interval=300ms",
        "proovy.run.coordinator.lease-ttl=2s",
        "proovy.run.coordinator.deadline-sweep-interval=200ms",
        "proovy.run.coordinator.min-dispatch-remaining=0s",
        "proovy.run.coordinator.redelivery.max-resends=4",
        "proovy.run.coordinator.redelivery.max-status-checks=2",
        "proovy.run.coordinator.redelivery.initial-backoff=50ms",
        "proovy.run.coordinator.redelivery.max-backoff=200ms",
        "proovy.run.coordinator.recovery.interval=200ms",
        "proovy.run.coordinator.recovery.max-recoveries=2",
        "proovy.run.coordinator.shutdown-timeout=3s"
})
class RunRecoveryPostgresTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final long DAILY_CREDITS = 100;
    private static final String OWNER = "t185-coordinator";

    private static PostgresTestDatabase db;
    private static FakeBrain brain;

    @BeforeAll
    static void setUp() {
        db = PostgresTestDatabase.createMigrated();
        db.runScript("db/seed/product-seed.sql");
        brain = new FakeBrain();
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
    @Autowired RunCoordinator coordinator;
    @Autowired RunClaimStore claims;
    @Autowired RunTerminalService terminals;
    @Autowired RequestUsageService usage;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired TransactionTemplate tx;
    @Autowired MeterRegistry meters;

    // ------------------------------------------------------------------
    // Server 재시작: Brain terminal 저장 후·실행 중·저장 전 소실
    // ------------------------------------------------------------------

    @Test
    void restartAfterBrainStoredTerminalRecoversTheStoredResultOnceWithoutAnotherExecution() {
        long user = newUser();
        RunAdmission run = admit(user, Step.HOLD);
        String fence = interruptByRestart(run, StatusMode.TERMINAL);

        awaitStatus(run.runId(), "COMPLETED");

        // 저장 terminal 회수: 새 stream(= 새 provider 실행) 없이 status 하나로 확정했다
        assertThat(brain.deliveries(run.requestId())).hasSize(1);
        assertThat(brain.statusCalls(run.runId().toString())).isGreaterThanOrEqualTo(1);
        Map<String, Object> row = runRow(run.runId());
        assertThat(row.get("attempt")).isEqualTo(1);
        assertThat(row.get("fence_token")).isEqualTo(fence);
        assertThat(row.get("recovery_count")).isEqualTo(1);
        assertThat(row.get("response_disposition")).isEqualTo("clarification_only");
        await(() -> runRow(run.runId()).get("runtime_cleanup_at") != null);
        // message·완료 event·보상이 한 번씩
        assertThat(jdbc.queryForObject("SELECT status FROM chat_messages WHERE message_id = ?", String.class,
                run.assistantMessageId())).isEqualTo("COMPLETED");
        assertThat(eventCount(run.runId(), "run.completed")).isEqualTo(1);
        assertThat(eventCount(run.runId(), "activity.started")).isEqualTo(1);
        assertThat(compensations(run.requestId())).isEqualTo(1);
        assertThat(credits(user)).isEqualTo(DAILY_CREDITS);

        // 중복 회수: terminal run은 후보도 인수 대상도 아니다
        assertThat(claims.findStale(50)).doesNotContain(run.runId());
        assertThat(claims.takeOver(run.runId(), OWNER, Duration.ofSeconds(2))).isEmpty();
        await(() -> !coordinator.isExecuting(run.runId()));
        assertThat(eventCount(run.runId(), "run.completed")).isEqualTo(1);
        assertThat(counter("recoveries.finished", "result", "SETTLED")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void restartWhileBrainStillExecutesReattachesToTheSameAttempt() {
        RunAdmission run = admit(newUser(), Step.HOLD, Step.COMPLETE);
        String fence = interruptByRestart(run, StatusMode.EXECUTING);

        awaitStatus(run.runId(), "COMPLETED");

        // 같은 attempt·fence·fingerprint로 다시 붙었다(Brain OBSERVE). 새 attempt·fence 없음
        List<Delivery> sent = brain.deliveries(run.requestId());
        assertThat(sent).hasSize(2);
        assertThat(sent).extracting(Delivery::attempt).containsOnly(1);
        assertThat(sent).extracting(Delivery::fenceToken).containsOnly(fence);
        assertThat(sent).extracting(Delivery::fingerprint).containsOnly(sent.get(0).fingerprint());
        // 재전달은 deadline을 늘리지 않고 같은 고정 값을 보낸다
        assertThat(sent).extracting(Delivery::deadline).containsOnly(sent.get(0).deadline());
        Map<String, Object> row = runRow(run.runId());
        assertThat(row.get("attempt")).isEqualTo(1);
        assertThat((Integer) row.get("retry_count")).isGreaterThanOrEqualTo(1);
        assertThat(eventCount(run.runId(), "activity.started")).isEqualTo(1);
        assertThat(eventCount(run.runId(), "run.completed")).isEqualTo(1);
    }

    @Test
    void executionLostAfterBrainStartedFailsOnceWithoutRestartingTheGraph() {
        long user = newUser();
        RunAdmission run = admit(user, Step.HOLD, Step.COMPLETE);
        double lostBefore = counter("execution_lost");
        // 재기동 뒤 Brain record가 없다(absent) — 그런데 이 attempt의 activity가 이미 저장돼 있다: 실행 소실
        interruptByRestart(run, StatusMode.ABSENT);

        awaitStatus(run.runId(), "FAILED");

        // 같은 요청을 다시 보내지 않았다(보냈다면 Brain이 새 graph를 START했다)
        assertThat(brain.deliveries(run.requestId())).hasSize(1);
        Map<String, Object> row = runRow(run.runId());
        assertThat(row.get("failure_code")).isEqualTo(RunFailureCode.PROVIDER_ERROR.wireValue());
        assertThat(row.get("attempt")).isEqualTo(1);
        assertThat(eventCount(run.runId(), "run.failed")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM chat_messages WHERE message_id = ?", String.class,
                run.assistantMessageId())).isEqualTo("FAILED");
        // 한 번 보상
        assertThat(compensations(run.requestId())).isEqualTo(1);
        assertThat(credits(user)).isEqualTo(DAILY_CREDITS);
        assertThat(counter("execution_lost")).isGreaterThan(lostBefore);
    }

    @Test
    void staleRunThatNeverReachedBrainIsDeliveredOnceAsItsFirstStart() {
        // 이전 프로세스가 claim만 하고 보내기 전에 사라졌다: lease 만료, Brain record·저장 event 없음 = 명확한 미시작
        coordinator.stop();
        RunAdmission run;
        ClaimedRun claimed;
        try {
            run = admit(newUser(), Step.COMPLETE);
            claimed = claimAsGoneInstance(run.runId());
        } finally {
            coordinator.start();
        }

        awaitStatus(run.runId(), "COMPLETED");

        assertThat(brain.deliveries(run.requestId())).singleElement().satisfies(d -> {
            assertThat(d.attempt()).isEqualTo(1);
            assertThat(d.fenceToken()).isEqualTo(claimed.fenceToken());
        });
        assertThat(brain.statusCalls(run.runId().toString())).isGreaterThanOrEqualTo(1);
        assertThat(runRow(run.runId()).get("recovery_count")).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // status 응답 유실·조회 한도·인수 한도
    // ------------------------------------------------------------------

    @Test
    void lostStatusResponsesAreRetriedWithinTheLimitThenTheStoredTerminalIsRecovered() {
        RunAdmission run = admit(newUser(), Step.HOLD);
        brain.failStatus(run.requestId(), 1);
        interruptByRestart(run, StatusMode.TERMINAL);

        awaitStatus(run.runId(), "COMPLETED");

        assertThat(brain.statusCalls(run.runId().toString())).isGreaterThanOrEqualTo(2);
        assertThat(brain.deliveries(run.requestId())).hasSize(1);
        assertThat(eventCount(run.runId(), "run.completed")).isEqualTo(1);
    }

    @Test
    void exhaustedStatusQueriesFailOnceWithCompensationAndNoResend() {
        long user = newUser();
        RunAdmission run = admit(user, Step.HOLD, Step.COMPLETE);
        brain.failStatus(run.requestId(), 1000);
        interruptByRestart(run, StatusMode.TERMINAL);

        awaitStatus(run.runId(), "FAILED");

        // max-status-checks(2)를 다 쓰면 불명확한 상태로 더 기다리지도, 새 실행을 만들지도 않는다
        assertThat(brain.deliveries(run.requestId())).hasSize(1);
        assertThat(brain.statusCalls(run.runId().toString())).isBetween(2L, 3L);
        assertThat(runRow(run.runId()).get("failure_code")).isEqualTo(RunFailureCode.PROVIDER_ERROR.wireValue());
        assertThat(compensations(run.requestId())).isEqualTo(1);
        assertThat(credits(user)).isEqualTo(DAILY_CREDITS);
    }

    @Test
    void recoveryLimitEndsWithOneFinalStatusCheckInsteadOfObservingAgain() {
        long user = newUser();
        RunAdmission run = admit(user, Step.HOLD, Step.COMPLETE);
        // 이전 재시작들이 인수 한도(2)를 다 썼다. 재시작이 한도를 초기화하지 않는다
        interruptByRestart(run, StatusMode.EXECUTING,
                () -> jdbc.update("UPDATE ai_runs SET recovery_count = 2 WHERE run_id = ?", run.runId()));

        awaitStatus(run.runId(), "FAILED");

        // Brain은 살아 있다고 답했지만 다시 붙지 않고(OBSERVE 없음) 실패로 수습했다
        assertThat(brain.deliveries(run.requestId())).hasSize(1);
        Map<String, Object> row = runRow(run.runId());
        assertThat(row.get("recovery_count")).isEqualTo(3);
        assertThat(row.get("failure_code")).isEqualTo(RunFailureCode.PROVIDER_ERROR.wireValue());
        assertThat(compensations(run.requestId())).isEqualTo(1);
        assertThat(credits(user)).isEqualTo(DAILY_CREDITS);
        assertThat(counter("recoveries", "result", "LIMIT_EXCEEDED")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void finalCheckQueriesStatusOnlyOnceBeforeFailing() {
        long user = newUser();
        RunAdmission run = admit(user, Step.HOLD, Step.COMPLETE);
        brain.failStatus(run.requestId(), 1000);
        interruptByRestart(run, StatusMode.TERMINAL,
                () -> jdbc.update("UPDATE ai_runs SET recovery_count = 2 WHERE run_id = ?", run.runId()));

        awaitStatus(run.runId(), "FAILED");

        // 인수 한도를 넘은 run은 max-status-checks(2)가 아니라 한 번만 조회한다
        assertThat(brain.statusCalls(run.runId().toString())).isEqualTo(1);
        assertThat(brain.deliveries(run.requestId())).hasSize(1);
        assertThat(compensations(run.requestId())).isEqualTo(1);
        assertThat(credits(user)).isEqualTo(DAILY_CREDITS);
    }

    @Test
    void recoveryCountIsMonotonic() {
        UUID runId = insertStaleRun(newUser(), Instant.now().plusSeconds(600));
        // 회수 주기가 먼저 끝내지 않게 다른 인스턴스의 살아 있는 lease로 둔다
        jdbc.update("UPDATE ai_runs SET lease_expires_at = now() + interval '10 minutes' WHERE run_id = ?", runId);
        try {
            jdbc.update("UPDATE ai_runs SET recovery_count = 5 WHERE run_id = ?", runId);
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    jdbc.update("UPDATE ai_runs SET recovery_count = 4 WHERE run_id = ?", runId))
                    .hasMessageContaining("recovery_count is monotonic");
        } finally {
            terminals.failByServer(runId, 1, "fence-gone", RunFailureCode.INTERNAL_ERROR, true);
        }
    }

    // ------------------------------------------------------------------
    // 경합: 중복 recovery worker·취소·deadline·살아 있는 lease
    // ------------------------------------------------------------------

    @Test
    void concurrentTakeoversHaveExactlyOneWinnerAndKeepAttemptAndFence() throws Exception {
        coordinator.stop();
        UUID stale = null;
        UUID canceling = null;
        UUID live = null;
        try {
            long user = newUser();
            stale = insertStaleRun(user, Instant.now().plusSeconds(600));
            canceling = insertStaleRun(user, Instant.now().plusSeconds(600));
            jdbc.update("""
                    UPDATE ai_runs SET status = 'CANCELING', cancel_cause = 'user_requested',
                           cancel_requested_from = 'RUNNING', cancel_requested_at = now()
                    WHERE run_id = ?
                    """, canceling);
            live = insertStaleRun(user, Instant.now().plusSeconds(600));
            jdbc.update("UPDATE ai_runs SET lease_expires_at = now() + interval '10 minutes' WHERE run_id = ?", live);
            assertThat(claims.acquireCoordinatorLease("rescuer", Duration.ofSeconds(30))).isTrue();

            UUID target = stale;
            List<Optional<StaleRun>> results = race(6, () -> claims.takeOver(target, "rescuer",
                    Duration.ofSeconds(30)));
            List<StaleRun> winners = results.stream().flatMap(Optional::stream).toList();
            assertThat(winners).singleElement().satisfies(w -> {
                assertThat(w.claim().attempt()).isEqualTo(1);
                assertThat(w.claim().fenceToken()).isEqualTo("fence-gone");
                assertThat(w.recoveryCount()).isEqualTo(1);
            });
            Map<String, Object> row = runRow(stale);
            assertThat(row.get("lease_owner")).isEqualTo("rescuer");
            assertThat(row.get("attempt")).isEqualTo(1);
            assertThat(row.get("recovery_count")).isEqualTo(1);

            // CANCELING(1.84 소유)·살아 있는 lease는 후보도 인수 대상도 아니다
            assertThat(claims.findStale(50)).doesNotContain(stale, canceling, live);
            assertThat(claims.takeOver(canceling, "rescuer", Duration.ofSeconds(30))).isEmpty();
            assertThat(claims.takeOver(live, "rescuer", Duration.ofSeconds(30))).isEmpty();
            assertThat(runRow(canceling).get("recovery_count")).isEqualTo(0);
            // coordinator lease가 없는 인스턴스는 인수하지 않는다
            jdbc.update("UPDATE ai_runs SET lease_expires_at = now() WHERE run_id = ?", stale);
            assertThat(claims.takeOver(stale, "not-the-leader", Duration.ofSeconds(30))).isEmpty();
            // 로컬에서 worker가 도는 run은 후보 SQL에서 빠진다(뒤쪽 후보를 가리지 않게)
            assertThat(claims.findStale(50)).contains(stale);
            assertThat(claims.findStale(50, List.of(stale))).doesNotContain(stale);
            claims.releaseCoordinatorLease("rescuer");
        } finally {
            if (stale != null) {
                terminals.failByServer(stale, 1, "fence-gone", RunFailureCode.INTERNAL_ERROR, true);
            }
            if (live != null) {
                terminals.failByServer(live, 1, "fence-gone", RunFailureCode.INTERNAL_ERROR, true);
            }
            if (canceling != null) {
                // 취소 확정은 1.84 몫이다. 테스트 정리로 terminal만 만든다(전역 슬롯 반환)
                jdbc.update("""
                        UPDATE ai_runs SET status = 'CANCELED', completed_at = now(), lease_owner = NULL,
                               lease_expires_at = NULL
                        WHERE run_id = ?
                        """, canceling);
            }
            coordinator.start();
        }
        await(coordinator::isLeader);
    }

    @Test
    void staleRunPastItsDeadlineIsExpiredByTheSweepAndNeverQueried() {
        long user = newUser();
        UUID runId = insertStaleRun(user, Instant.now().minusSeconds(1));

        awaitStatus(runId, "FAILED");

        assertThat(runRow(runId).get("failure_code")).isEqualTo(RunFailureCode.TIMEOUT.wireValue());
        assertThat(runRow(runId).get("recovery_count")).isEqualTo(0);
        assertThat(brain.statusCalls(runId.toString())).isZero();
        assertThat(credits(user)).isEqualTo(DAILY_CREDITS);
    }

    @Test
    void liveLocalWorkerWithALateHeartbeatIsNotTakenOver() {
        RunAdmission run = admit(newUser(), Step.HOLD);
        try {
            await(() -> !brain.deliveries(run.requestId()).isEmpty());
            assertThat(coordinator.isExecuting(run.runId())).isTrue();
            // heartbeat가 늦어 lease가 만료돼 보여도 이 인스턴스에 worker가 있으면 회수하지 않는다
            jdbc.update("UPDATE ai_runs SET lease_expires_at = now() WHERE run_id = ?", run.runId());
            coordinator.recoverStale();
            assertThat(runRow(run.runId()).get("recovery_count")).isEqualTo(0);
            assertThat(brain.statusCalls(run.runId().toString())).isZero();
        } finally {
            brain.release(run.requestId());
        }
        awaitStatus(run.runId(), "COMPLETED");
        assertThat(brain.deliveries(run.requestId())).hasSize(1);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * stream 중 Server를 재시작한다: 진행 중 worker는 멈추고 lease만 만료된다. 멈춘 동안 Brain status를 {@code mode}로 바꾸고
     * 붙잡힌 이전 stream을 놓아준다(끊긴 연결이라 Server에 닿지 않는다). 그 run의 fence를 돌려준다.
     */
    private String interruptByRestart(RunAdmission run, StatusMode mode) {
        return interruptByRestart(run, mode, () -> { });
    }

    private String interruptByRestart(RunAdmission run, StatusMode mode, Runnable whileStopped) {
        await(() -> !brain.deliveries(run.requestId()).isEmpty()
                && eventCount(run.runId(), "activity.started") == 1);
        coordinator.stop();
        try {
            Map<String, Object> row = runRow(run.runId());
            assertThat(row.get("status")).isIn("RUNNING", "RETRYING");
            assertThat(jdbc.queryForObject("SELECT lease_expires_at <= now() FROM ai_runs WHERE run_id = ?",
                    Boolean.class, run.runId())).isTrue();
            brain.status(run.requestId(), mode);
            whileStopped.run();
            return (String) row.get("fence_token");
        } finally {
            brain.release(run.requestId());
            coordinator.start();
        }
    }

    /** coordinator가 멈춘 동안 다른 owner로 claim하고 lease를 만료시킨다(보내기 전에 사라진 이전 프로세스). */
    private ClaimedRun claimAsGoneInstance(UUID runId) {
        assertThat(claims.acquireCoordinatorLease("gone-instance", Duration.ofSeconds(30))).isTrue();
        ClaimedRun claimed = claims.claimNext("gone-instance", "fence-gone-" + UUID.randomUUID(),
                Duration.ofSeconds(30), Duration.ZERO).orElseThrow();
        assertThat(claimed.runId()).isEqualTo(runId);
        assertThat(claims.expireLease(claimed, "gone-instance")).isTrue();
        claims.releaseCoordinatorLease("gone-instance");
        return claimed;
    }

    private RunAdmission admit(long user, Step... steps) {
        String requestId = UUID.randomUUID().toString();
        brain.script(requestId, steps);
        ObjectNode body = mapper.createObjectNode();
        body.put("requestId", requestId);
        body.put("text", "x^2-5x+6=0을 풀어줘 " + UUID.randomUUID());
        return admission.admit(user, body);
    }

    private long newUser() {
        String key = UUID.randomUUID().toString();
        long user = jdbc.queryForObject("""
                INSERT INTO users (nickname, provider, provider_user_id, email, created_at, updated_at)
                VALUES ('t185', 'KAKAO', ?, ?, now(), now()) RETURNING user_id
                """, Long.class, key, key + "@example.test");
        jdbc.update("""
                INSERT INTO credit_balance (user_id, daily_free_credit, daily_free_limit, daily_expires_at, free_credit,
                                            paid_credit, created_at, updated_at)
                VALUES (?, ?, ?, now() + interval '1 day', 0, 0, now(), now())
                """, user, DAILY_CREDITS, DAILY_CREDITS);
        return user;
    }

    /**
     * 이전 프로세스가 claim한 뒤 사라진 run(attempt 1, fence-gone, lease 만료, 최소 snapshot)·최초 차감. PENDING으로
     * commit하면 coordinator가 먼저 claim할 수 있어 한 transaction에서 RUNNING으로 만든다.
     */
    private UUID insertStaleRun(long user, Instant deadline) {
        return tx.execute(status -> {
            long note = jdbc.queryForObject("""
                    INSERT INTO notes (user_id, title, created_at, updated_at) VALUES (?, 't185', now(), now())
                    RETURNING note_id
                    """, Long.class, user);
            UUID userMessage = UUID.randomUUID();
            UUID assistantMessage = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO chat_messages (message_id, note_id, role, status, user_input, plain_text, created_at)
                    VALUES (?, ?, 'USER', 'COMPLETED', '{"text":"풀어줘"}', '풀어줘', now()),
                           (?, ?, 'ASSISTANT', 'PENDING', NULL, NULL, now() + interval '1 microsecond')
                    """, userMessage, note, assistantMessage, note);
            UUID runId = UUID.randomUUID();
            String requestId = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO ai_runs (run_id, request_id, user_id, note_id, user_message_id, assistant_message_id,
                                         request_fingerprint, request_snapshot, policy_snapshot, budget_policy_version,
                                         request_deadline_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, '{"input":{"text":"풀어줘"}}', '{"limits":{}}', 'budget-v1', ?, now())
                    """, runId, requestId, user, note, userMessage, assistantMessage, "f".repeat(64),
                    OffsetDateTime.ofInstant(deadline.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC));
            usage.chargeRequest(user, requestId, runId);
            jdbc.update("""
                    UPDATE ai_runs SET status = 'RUNNING', attempt = 1, fence_token = 'fence-gone', started_at = now(),
                           lease_owner = 'gone-instance', lease_expires_at = now() - interval '1 second',
                           heartbeat_at = now() - interval '1 minute'
                    WHERE run_id = ?
                    """, runId);
            return runId;
        });
    }

    private Map<String, Object> runRow(UUID runId) {
        return jdbc.queryForMap("SELECT * FROM ai_runs WHERE run_id = ?", runId);
    }

    private long eventCount(UUID runId, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM ai_run_events WHERE run_id = ? AND event_type = ?",
                Long.class, runId, type);
    }

    private long compensations(String requestId) {
        return jdbc.queryForObject("SELECT count(*) FROM request_usage_compensations WHERE request_id = ?",
                Long.class, requestId);
    }

    private long credits(long user) {
        return jdbc.queryForObject("SELECT daily_free_credit + free_credit + paid_credit FROM credit_balance "
                + "WHERE user_id = ?", Long.class, user);
    }

    private double counter(String name, String... tags) {
        var counter = meters.find("proovy.run.coordinator." + name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private void awaitStatus(UUID runId, String status) {
        await(() -> status.equals(runRow(runId).get("status")));
    }

    private static void await(BooleanSupplier condition) {
        Instant until = Instant.now().plus(WAIT);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(until)) {
                throw new AssertionError("condition not met within " + WAIT);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private static <T> List<T> race(int workers, java.util.concurrent.Callable<T> task) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(workers);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return task.call();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
