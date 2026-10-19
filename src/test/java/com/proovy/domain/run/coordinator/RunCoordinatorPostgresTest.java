package com.proovy.domain.run.coordinator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proovy.domain.run.admission.RunAdmission;
import com.proovy.domain.run.admission.RunAdmissionService;
import com.proovy.domain.run.coordinator.FakeBrain.Delivery;
import com.proovy.domain.run.coordinator.FakeBrain.StatusMode;
import com.proovy.domain.run.coordinator.FakeBrain.Step;
import com.proovy.domain.run.entity.RunFailureCode;
import com.proovy.domain.run.event.RunEventStore;
import com.proovy.domain.run.terminal.RunTerminalService;
import com.proovy.domain.usage.service.RequestUsageService;
import com.proovy.support.db.PostgresTestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 실제 Postgres + 실제 {@code BrainRunClient} + loopback 가짜 Brain으로 coordinator(1.83)를 검사한다. 브라우저·SSE
 * 구독은 없다 — 접수(1.79)가 남긴 PENDING run을 coordinator가 스스로 가져가 terminal 확정(1.82)까지 잇는지 본다.
 * 슬롯 2, 짧은 heartbeat·backoff로 포화·capacity·단절·영구 오류·deadline·동시 claim·종료를 주입한다.
 */
@Tag("postgres")
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
        "proovy.brain.auth.shared-token=t183-shared-token",
        "proovy.brain.stream-idle-timeout=3s",
        "proovy.run.coordinator.enabled=true",
        "proovy.run.coordinator.instance-id=t183-coordinator",
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
        "proovy.run.coordinator.shutdown-timeout=3s"
})
class RunCoordinatorPostgresTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final long DAILY_CREDITS = 100;

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
    /** 실제 저장소 그대로, 특정 테스트에서만 일시 실패를 주입한다. */
    @MockitoSpyBean RunEventStore events;
    @Autowired RequestUsageService usage;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired TransactionTemplate tx;

    // ------------------------------------------------------------------
    // 브라우저 독립 진행과 terminal 연결
    // ------------------------------------------------------------------

    @Test
    void admittedRunProgressesWithoutAnyBrowserAndSettlesThroughTerminalService() {
        long user = newUser();
        RunAdmission run = admit(user);

        awaitStatus(run.runId(), "COMPLETED");
        // cleanup 예약은 terminal commit 뒤 worker가 따로 기록한다
        await(() -> runRow(run.runId()).get("runtime_cleanup_at") != null);

        Map<String, Object> row = runRow(run.runId());
        List<Delivery> sent = brain.deliveries(run.requestId());
        assertThat(sent).hasSize(1);
        Delivery delivery = sent.get(0);
        // 저장 snapshot·deadline·누적 예산을 그대로, attempt·fence는 claim이 배정한 값으로 보낸다
        assertThat(delivery.attempt()).isEqualTo(1).isEqualTo(row.get("attempt"));
        assertThat(delivery.fenceToken()).isEqualTo(row.get("fence_token"));
        assertThat(delivery.fingerprint()).isEqualTo(row.get("request_fingerprint"));
        assertThat(OffsetDateTime.parse(delivery.deadline()).toInstant())
                .isEqualTo(instant(row.get("request_deadline_at")).truncatedTo(ChronoUnit.MICROS));
        assertThat(delivery.usage().get("used_llm_calls").asLong()).isZero();
        assertThat(delivery.usage().get("used_cost_micros").asLong()).isZero();

        // 1.82 확정: message·event·보상(clarification → 순사용량 0)·lease 해제, coordinator의 cleanup 예약
        assertThat(row.get("response_disposition")).isEqualTo("clarification_only");
        assertThat(row.get("lease_owner")).isNull();
        assertThat(row.get("runtime_cleanup_at")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT status FROM chat_messages WHERE message_id = ?", String.class,
                run.assistantMessageId())).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForList("SELECT event_type FROM ai_run_events WHERE run_id = ? ORDER BY seq",
                String.class, run.runId()))
                .containsExactly("run.created", "activity.started", "response.page.started",
                        "response.page.completed", "run.completed");
        assertThat(credits(user)).isEqualTo(DAILY_CREDITS);
    }

    @Test
    void streamingHoldsNoRowLockAndKeepsHeartbeating() throws Exception {
        RunAdmission run = admit(newUser(), Step.HOLD);
        await(() -> !brain.deliveries(run.requestId()).isEmpty());
        Instant firstBeat = instant(runRow(run.runId()).get("heartbeat_at"));

        // stream 소비 중 run 행을 다른 transaction이 잠글 수 있다(긴 transaction·row lock 없음). heartbeat의 단일
        // UPDATE와 순간 겹칠 수 있어 짧게 다시 시도한다 — 계속 잡혀 있다면 모두 실패한다.
        assertThat(lockableWithin(run.runId(), 10)).isTrue();
        // stream을 1.5초 붙잡는 동안 1초 넘게 열린 transaction이 없다(claim 같은 짧은 다중 문장 transaction은 제외된다)
        Thread.sleep(1500);
        assertThat(jdbc.queryForList("""
                SELECT left(query, 200) FROM pg_stat_activity
                WHERE datname = current_database() AND pid <> pg_backend_pid() AND xact_start IS NOT NULL
                  AND now() - xact_start > interval '1 second'
                """, String.class)).isEmpty();
        await(() -> instant(runRow(run.runId()).get("heartbeat_at")).isAfter(firstBeat));
        assertThat(coordinator.isExecuting(run.runId())).isTrue();

        brain.release(run.requestId());
        awaitStatus(run.runId(), "COMPLETED");
        await(() -> !coordinator.isExecuting(run.runId()));
    }

    // ------------------------------------------------------------------
    // 전역 유한 슬롯
    // ------------------------------------------------------------------

    @Test
    void saturatedSlotsLeaveRunsPendingWithoutQueueOrDuplicateExecution() throws Exception {
        long user = newUser();
        brain.resetInFlightPeak();
        List<RunAdmission> runs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            RunAdmission run = admit(user, Step.HOLD);
            runs.add(run);
        }
        await(() -> sentCount(runs) == 2);
        Thread.sleep(800);
        assertThat(sentCount(runs)).isEqualTo(2);
        assertThat(statuses(runs)).containsOnly("RUNNING", "PENDING");
        assertThat(statuses(runs).stream().filter("PENDING"::equals)).hasSize(2);
        assertThat(coordinator.slotsInUse()).isEqualTo(2);

        for (RunAdmission run : runs) {
            brain.release(run.requestId());
        }
        for (RunAdmission run : runs) {
            awaitStatus(run.runId(), "COMPLETED");
        }
        // 각 run은 Brain에 정확히 한 번, attempt 1로만 전달됐고 동시 stream은 슬롯 수를 넘지 않았다
        for (RunAdmission run : runs) {
            assertThat(brain.deliveries(run.requestId())).singleElement()
                    .satisfies(d -> assertThat(d.attempt()).isEqualTo(1));
        }
        assertThat(brain.maxInFlight()).isLessThanOrEqualTo(2);
        await(() -> coordinator.slotsInUse() == 0);
    }

    @Test
    void foreignExecutionKeepsItsGlobalSlotUntilTerminal() throws Exception {
        long user = newUser();
        // 다른 인스턴스가 claim해 실행 중인 run: attempt 1, 이 coordinator 밖의 lease. Brain에서 돌고 있다.
        // (lease가 만료된 고아는 이제 1.85 회수가 인수해 수습한다 — RunRecoveryPostgresTest)
        // PENDING으로 commit하면 coordinator가 먼저 claim할 수 있어 같은 transaction에서 RUNNING으로 만든다.
        UUID orphan = tx.execute(status -> {
            UUID runId = insertPendingRunInTransaction(user, Instant.now().plusSeconds(600));
            jdbc.update("""
                    UPDATE ai_runs SET status = 'RUNNING', attempt = 1, fence_token = 'fence-gone', started_at = now(),
                           lease_owner = 'other-instance', lease_expires_at = now() + interval '10 minutes',
                           heartbeat_at = now()
                    WHERE run_id = ?
                    """, runId);
            return runId;
        });
        RunAdmission first = admit(user, Step.HOLD);
        RunAdmission second = admit(user, Step.HOLD);
        List<RunAdmission> runs = List.of(first, second);
        try {
            // 슬롯 2 중 1은 다른 인스턴스의 run이 차지한다: 이 인스턴스의 permit과 무관하게 terminal 전까지 센다
            await(() -> sentCount(runs) == 1);
            Thread.sleep(800);
            assertThat(sentCount(runs)).isEqualTo(1);
            assertThat(claims.countActiveExecutions()).isEqualTo(2);

            // 그 run이 terminal이 되면 슬롯이 풀려 다음 run이 나간다
            terminals.failByServer(orphan, 1, "fence-gone", RunFailureCode.INTERNAL_ERROR, true);
            await(() -> sentCount(runs) == 2);
        } finally {
            terminals.failByServer(orphan, 1, "fence-gone", RunFailureCode.INTERNAL_ERROR, true);
            runs.forEach(run -> brain.release(run.requestId()));
        }
        for (RunAdmission run : runs) {
            awaitStatus(run.runId(), "COMPLETED");
        }
    }

    // ------------------------------------------------------------------
    // capacity·단절·영구 오류
    // ------------------------------------------------------------------

    @Test
    void brainCapacityIsRetriedWithTheSameAttemptAndFence() {
        RunAdmission run = admit(newUser(), Step.CAPACITY, Step.CAPACITY, Step.COMPLETE);

        awaitStatus(run.runId(), "COMPLETED");

        List<Delivery> sent = brain.deliveries(run.requestId());
        assertThat(sent).hasSize(3);
        assertThat(sent).extracting(Delivery::attempt).containsOnly(1);
        assertThat(sent).extracting(Delivery::fenceToken).containsOnly(sent.get(0).fenceToken());
        assertThat(sent).extracting(Delivery::fingerprint).containsOnly(sent.get(0).fingerprint());
        Map<String, Object> row = runRow(run.runId());
        assertThat(row.get("attempt")).isEqualTo(1);
        // 재전달마다 retry_count + 1. 서버에 닿지 않은 연결 실패도 같은 요청 재전송이라 2 이상일 수 있다
        assertThat((Integer) row.get("retry_count")).isGreaterThanOrEqualTo(2);
    }

    @Test
    void endlessCapacityIsBoundedAndFailsWithOneCompensation() {
        long user = newUser();
        RunAdmission run = admit(user, Step.CAPACITY);

        awaitStatus(run.runId(), "FAILED");

        // 최초 1번 + max-resends 4번까지만. 무한 재시도·새 attempt 없음
        assertThat(brain.deliveries(run.requestId())).hasSizeBetween(1, 5)
                .extracting(Delivery::attempt).containsOnly(1);
        Map<String, Object> row = runRow(run.runId());
        assertThat(row.get("retry_count")).isEqualTo(4);
        assertThat(row.get("failure_code")).isEqualTo(RunFailureCode.PROVIDER_ERROR.wireValue());
        assertThat(row.get("attempt")).isEqualTo(1);
        assertThat(credits(user)).isEqualTo(DAILY_CREDITS);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM request_usage_compensations WHERE request_id = ?",
                Long.class, run.requestId())).isEqualTo(1);
    }

    @Test
    void streamEndingWithoutTerminalIsNotSuccessAndReattachesToTheLiveExecution() {
        RunAdmission run = admit(newUser(), StatusMode.EXECUTING, Step.END_WITHOUT_TERMINAL, Step.COMPLETE);

        awaitStatus(run.runId(), "COMPLETED");

        assertThat(brain.statusCalls(run.runId().toString())).isGreaterThanOrEqualTo(1);
        List<Delivery> sent = brain.deliveries(run.requestId());
        assertThat(sent).hasSize(2).extracting(Delivery::attempt).containsOnly(1);
        assertThat(sent).extracting(Delivery::fenceToken).containsOnly(sent.get(0).fenceToken());
        // 같은 activity 재전달은 dedup된다
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_run_events WHERE run_id = ? AND event_type = "
                + "'activity.started'", Long.class, run.runId())).isEqualTo(1);
    }

    @Test
    void storedTerminalFromStatusIsSettledWithoutAnotherStream() {
        RunAdmission run = admit(newUser(), StatusMode.TERMINAL, Step.END_WITHOUT_TERMINAL);

        awaitStatus(run.runId(), "COMPLETED");

        assertThat(brain.deliveries(run.requestId())).hasSize(1);
        assertThat(runRow(run.runId()).get("response_disposition")).isEqualTo("clarification_only");
    }

    @Test
    void absentRecordAfterTransportLossResendsTheSameRequest() {
        // frame 하나 없이 끊긴 전송 + absent = 명확한 미시작. 같은 요청이 첫 START다.
        // (frame을 받은 뒤의 absent는 실행 소실 — RunRecoveryPostgresTest)
        RunAdmission run = admit(newUser(), StatusMode.ABSENT, Step.DROP, Step.COMPLETE);

        awaitStatus(run.runId(), "COMPLETED");

        assertThat(brain.deliveries(run.requestId())).hasSize(2).extracting(Delivery::attempt).containsOnly(1);
    }

    @Test
    void transactionAcquisitionFailureDuringStreamReconcilesInsteadOfAbandoningTheRun() {
        String requestId = UUID.randomUUID().toString();
        // 첫 Activity 저장에서 transaction을 열지 못한다(연결 획득 실패). 다음 시도부터는 실제 저장소
        doThrow(new CannotCreateTransactionException("connection unavailable")).doCallRealMethod()
                .when(events).appendActivity(argThat(event -> event != null && event.runId() != null
                        && brain.deliveries(requestId).stream().anyMatch(d -> d.runId().equals(event.runId()))));
        RunAdmission run = admit(newUser(), requestId, StatusMode.EXECUTING, Step.COMPLETE, Step.COMPLETE);

        awaitStatus(run.runId(), "COMPLETED");

        // LOCAL_ERROR로 버리지 않고 같은 attempt로 status 수습 뒤 다시 붙었다
        assertThat(brain.statusCalls(run.runId().toString())).isGreaterThanOrEqualTo(1);
        assertThat(brain.deliveries(requestId)).hasSize(2).extracting(Delivery::attempt).containsOnly(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_run_events WHERE run_id = ? AND event_type = "
                + "'activity.started'", Long.class, run.runId())).isEqualTo(1);
    }

    @Test
    void permanentBrainErrorFailsWithoutResend() {
        long user = newUser();
        RunAdmission run = admit(user, Step.BAD_REQUEST);

        awaitStatus(run.runId(), "FAILED");

        assertThat(brain.deliveries(run.requestId())).hasSize(1);
        assertThat(runRow(run.runId()).get("failure_code")).isEqualTo(RunFailureCode.INTERNAL_ERROR.wireValue());
        assertThat(credits(user)).isEqualTo(DAILY_CREDITS);
    }

    @Test
    void rejectedTerminalIsNotPublishedAndFailsByServer() {
        long user = newUser();
        RunAdmission run = admit(user, Step.OUT_OF_SCOPE_TERMINAL);

        awaitStatus(run.runId(), "FAILED");

        assertThat(runRow(run.runId()).get("failure_code")).isEqualTo(RunFailureCode.INTERNAL_ERROR.wireValue());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM verified_solutions WHERE assistant_message_id = ?",
                Long.class, run.assistantMessageId())).isZero();
        assertThat(credits(user)).isEqualTo(DAILY_CREDITS);
    }

    // ------------------------------------------------------------------
    // deadline·동시 claim·종료
    // ------------------------------------------------------------------

    @Test
    void pendingRunPastDeadlineIsExpiredWithoutDispatch() {
        long user = newUser();
        UUID runId = insertPendingRun(user, Instant.now().minusSeconds(1));

        awaitStatus(runId, "FAILED");

        assertThat(runRow(runId).get("failure_code")).isEqualTo(RunFailureCode.TIMEOUT.wireValue());
        assertThat(brain.deliveries()).noneMatch(d -> d.runId().equals(runId.toString()));
        assertThat(credits(user)).isEqualTo(DAILY_CREDITS);
    }

    @Test
    void claimsAreExclusiveAndOnlyTheLeaseHolderClaims() throws Exception {
        coordinator.stop();
        try {
            List<UUID> pending = new ArrayList<>();
            long user = newUser();
            for (int i = 0; i < 3; i++) {
                pending.add(insertPendingRun(user, Instant.now().plusSeconds(600)));
            }
            assertThat(claims.acquireCoordinatorLease("owner-a", Duration.ofSeconds(30))).isTrue();
            // lease가 살아 있는 동안 다른 coordinator는 lease도 claim도 얻지 못한다
            assertThat(claims.acquireCoordinatorLease("owner-b", Duration.ofSeconds(30))).isFalse();
            assertThat(claims.claimNext("owner-b", "fence-b", Duration.ofSeconds(30), Duration.ZERO)).isEmpty();

            List<Optional<ClaimedRun>> results = race(6, i -> () -> claims.claimNext("owner-a", "fence-a-" + i,
                    Duration.ofSeconds(30), Duration.ZERO));
            List<UUID> claimed = results.stream().flatMap(Optional::stream).map(ClaimedRun::runId).toList();
            assertThat(claimed).hasSize(3).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(pending);
            for (UUID runId : pending) {
                Map<String, Object> row = runRow(runId);
                assertThat(row.get("status")).isEqualTo("RUNNING");
                assertThat(row.get("attempt")).isEqualTo(1);
                assertThat(row.get("lease_owner")).isEqualTo("owner-a");
                terminals.failByServer(runId, 1, (String) row.get("fence_token"), RunFailureCode.INTERNAL_ERROR,
                        true);
            }
            claims.releaseCoordinatorLease("owner-a");
        } finally {
            coordinator.start();
        }
        await(coordinator::isLeader);
    }

    @Test
    void shutdownStopsWorkersWithoutTreatingTransportEndAsSuccess() {
        RunAdmission run = admit(newUser(), Step.HOLD);
        await(() -> !brain.deliveries(run.requestId()).isEmpty());

        coordinator.stop();
        try {
            Map<String, Object> row = runRow(run.runId());
            // terminal이 아니다: run은 진행 중 상태로 남고 lease만 즉시 만료된다(재기동 뒤 1.85 회수 대상)
            assertThat(row.get("status")).isEqualTo("RUNNING");
            assertThat(row.get("completed_at")).isNull();
            assertThat(jdbc.queryForObject("SELECT lease_expires_at <= now() FROM ai_runs WHERE run_id = ?",
                    Boolean.class, run.runId())).isTrue();
            assertThat(coordinator.slotsInUse()).isZero();
            assertThat(coordinator.isExecuting(run.runId())).isFalse();
            // 로컬 permit은 돌려줬지만 Brain에서 아직 돌 수 있는 run이라 전역 슬롯은 계속 차지한다
            assertThat(claims.countActiveExecutions()).isGreaterThanOrEqualTo(1);
            // coordinator lease를 내놓아 다음 인스턴스가 TTL을 기다리지 않는다
            assertThat(claims.acquireCoordinatorLease("next-instance", Duration.ofSeconds(1))).isTrue();
            claims.releaseCoordinatorLease("next-instance");
        } finally {
            brain.release(run.requestId());
            coordinator.start();
        }
        // 재기동 뒤 회수가 인수해 terminal로 끝낸다(status absent + 저장된 activity = 실행 소실, RunRecoveryPostgresTest).
        // 회수 주기 전에 테스트가 먼저 끝내도 결과는 terminal 하나다.
        Map<String, Object> row = runRow(run.runId());
        terminals.failByServer(run.runId(), 1, (String) row.get("fence_token"), RunFailureCode.INTERNAL_ERROR, true);
        awaitStatus(run.runId(), "FAILED");
        await(coordinator::isLeader);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private RunAdmission admit(long user, Step... steps) {
        return admit(user, null, steps);
    }

    /** Brain 각본·status 모드를 먼저 건 뒤 접수한다(접수 commit 직후 coordinator가 바로 가져갈 수 있다). */
    private RunAdmission admit(long user, StatusMode status, Step... steps) {
        return admit(user, UUID.randomUUID().toString(), status, steps);
    }

    private RunAdmission admit(long user, String requestId, StatusMode status, Step... steps) {
        if (status != null) {
            brain.status(requestId, status);
        }
        if (steps.length > 0) {
            brain.script(requestId, steps);
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("requestId", requestId);
        body.put("text", "x^2-5x+6=0을 풀어줘 " + UUID.randomUUID());
        return admission.admit(user, body);
    }

    private long newUser() {
        String key = UUID.randomUUID().toString();
        long user = jdbc.queryForObject("""
                INSERT INTO users (nickname, provider, provider_user_id, email, created_at, updated_at)
                VALUES ('t183', 'KAKAO', ?, ?, now(), now()) RETURNING user_id
                """, Long.class, key, key + "@example.test");
        jdbc.update("""
                INSERT INTO credit_balance (user_id, daily_free_credit, daily_free_limit, daily_expires_at, free_credit,
                                            paid_credit, created_at, updated_at)
                VALUES (?, ?, ?, now() + interval '1 day', 0, 0, now(), now())
                """, user, DAILY_CREDITS, DAILY_CREDITS);
        return user;
    }

    /** 접수가 남기는 모양의 PENDING run(snapshot은 최소)·최초 차감. dispatch되지 않는 경로 검사용. */
    private UUID insertPendingRun(long user, Instant deadline) {
        return tx.execute(status -> insertPendingRunInTransaction(user, deadline));
    }

    private UUID insertPendingRunInTransaction(long user, Instant deadline) {
        long note = jdbc.queryForObject("""
                INSERT INTO notes (user_id, title, created_at, updated_at) VALUES (?, 't183', now(), now())
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
        return runId;
    }

    private Map<String, Object> runRow(UUID runId) {
        return jdbc.queryForMap("SELECT * FROM ai_runs WHERE run_id = ?", runId);
    }

    private boolean lockableWithin(UUID runId, int tries) throws Exception {
        for (int i = 0; i < tries; i++) {
            try (Connection c = db.connect()) {
                c.setAutoCommit(false);
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT status FROM ai_runs WHERE run_id = '" + runId
                             + "' FOR UPDATE NOWAIT")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).isEqualTo("RUNNING");
                    return true;
                } catch (java.sql.SQLException locked) {
                    Thread.sleep(50);
                } finally {
                    c.rollback();
                }
            }
        }
        return false;
    }

    private static Instant instant(Object timestamp) {
        return timestamp instanceof OffsetDateTime odt ? odt.toInstant() : ((java.sql.Timestamp) timestamp).toInstant();
    }

    private List<String> statuses(List<RunAdmission> runs) {
        return runs.stream().map(run -> (String) runRow(run.runId()).get("status")).toList();
    }

    private long sentCount(List<RunAdmission> runs) {
        Set<String> ids = new HashSet<>();
        runs.forEach(run -> ids.add(run.requestId()));
        return brain.deliveries().stream().filter(d -> ids.contains(d.requestId())).count();
    }

    private long credits(long user) {
        return jdbc.queryForObject("SELECT daily_free_credit + free_credit + paid_credit FROM credit_balance "
                + "WHERE user_id = ?", Long.class, user);
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

    private interface Task<T> {
        Callable<T> at(int index);
    }

    private static <T> List<T> race(int workers, Task<T> task) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(workers);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                Callable<T> work = task.at(i);
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return work.call();
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
