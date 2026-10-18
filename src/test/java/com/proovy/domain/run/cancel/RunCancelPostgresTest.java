package com.proovy.domain.run.cancel;

import static com.proovy.domain.run.contract.BrowserRunContractTestSupport.load;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proovy.domain.auth.service.AccessTokenBlacklistService;
import com.proovy.domain.auth.service.JwtTokenProvider;
import com.proovy.domain.run.admission.RunAdmission;
import com.proovy.domain.run.admission.RunAdmissionService;
import com.proovy.domain.run.brain.InternalRunWire;
import com.proovy.domain.run.brain.SourceEvent;
import com.proovy.domain.run.cancel.CancelFakeBrain.CancelMode;
import com.proovy.domain.run.contract.RunCancelResult;
import com.proovy.domain.run.coordinator.ClaimedRun;
import com.proovy.domain.run.coordinator.RunClaimStore;
import com.proovy.domain.run.entity.RunFailureCode;
import com.proovy.domain.run.event.ActivityAppendResult;
import com.proovy.domain.run.event.RunEventStore;
import com.proovy.domain.run.terminal.RunTerminalService;
import com.proovy.domain.run.terminal.TerminalSettlement;
import com.proovy.domain.run.terminal.TerminalSettlement.Outcome;
import com.proovy.domain.usage.service.RequestUsageService;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.response.ErrorCode;
import com.proovy.support.db.PostgresTestDatabase;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사용자 취소(1.84)를 실제 Postgres + 실제 HTTP(임의 port) + 실제 {@code BrainRunClient}·loopback 가짜 Brain으로 검사한다.
 * coordinator는 끈다 — PENDING은 그대로 남고, RUNNING은 claim SQL로 만든다(worker와의 end-to-end는
 * {@link RunCancelCoordinatorPostgresTest}). 판정표(cancel/cases.json)는 production endpoint로 돌린다.
 * <p>
 * cleanup-timeout 5s, 확인 주기 200ms, 사용자별 접수 제한 3건/1분(반복 요청 제한 검사용 — 다른 테스트는 사용자마다 3건 이하).
 */
@Tag("postgres")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
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
        "proovy.run.coordinator.enabled=false",
        "proovy.run.admission.rate-limit.max-requests=3",
        "proovy.run.admission.rate-limit.window=1m",
        "proovy.run.cancel.sweep-interval=200ms",
        "proovy.run.cancel.cleanup-timeout=5s"
})
class RunCancelPostgresTest {

    private static final String FENCE = "fence-t184";
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(5);
    private static final long DAILY_CREDITS = 100;
    private static final ObjectMapper RAW = new ObjectMapper();

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

    @LocalServerPort int port;
    @MockitoBean AccessTokenBlacklistService blacklist;
    @Autowired JwtTokenProvider tokens;
    @Autowired RunAdmissionService admission;
    @Autowired RunCancelService cancels;
    @Autowired RunTerminalService terminals;
    @Autowired RunEventStore events;
    @Autowired RequestUsageService usage;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper mapper;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    // ------------------------------------------------------------------
    // 판정표 (browser-run-v1 cancel/cases.json) — production endpoint
    // ------------------------------------------------------------------

    @Test
    @DisplayName("cancel/cases.json 9 case를 실제 HTTP endpoint로: body 검사가 run 조회보다 먼저, 멱등·terminal·404 동일 body")
    void decisionTableRunsThroughProductionEndpoint() throws Exception {
        JsonNode table = load("cancel/cases.json");
        List<String> notFoundBodies = new ArrayList<>();
        for (JsonNode testCase : table.get("cases")) {
            String name = testCase.get("name").asText();
            long owner = newUser();
            UUID runId = switch (testCase.get("run").asText()) {
                case "running" -> runningRun(owner, CancelMode.NEVER_CONFIRM);
                case "canceling" -> {
                    UUID id = runningRun(owner, CancelMode.NEVER_CONFIRM);
                    cancels.cancel(owner, id.toString(), null);
                    yield id;
                }
                case "completed" -> completedRun(owner);
                case "pending" -> admit(owner).runId();
                case "missing" -> UUID.randomUUID();
                case "other_user" -> admit(newUser()).runId();
                default -> throw new IllegalArgumentException(name);
            };
            JsonNode rawBody = testCase.get("rawBody");
            HttpResponse<String> response = postCancel(token(owner), runId.toString(),
                    rawBody.isNull() ? null : rawBody.asText());
            JsonNode expected = testCase.get("expected");
            assertThat(response.statusCode()).as(name + " " + response.body()).isEqualTo(expected.get("http").asInt());
            assertThat(response.headers().firstValue("Content-Type").orElse("")).as(name).startsWith("application/json");
            JsonNode body = RAW.readTree(response.body());
            if (expected.has("fixture")) {
                ObjectNode fixture = (ObjectNode) load(expected.get("fixture").asText() + ".json").get("body").deepCopy();
                ((ObjectNode) fixture.get("result")).put("runId", runId.toString());
                assertThat(body).as(name).isEqualTo(fixture);
                assertThat(body.at("/result/status").asText()).isEqualTo(expected.get("status").asText());
            } else {
                assertThat(body.get("isSuccess").asBoolean()).as(name).isFalse();
                assertThat(body.get("code").asText()).as(name).isEqualTo(expected.get("code").asText());
                if (expected.get("http").asInt() == 404) {
                    notFoundBodies.add(response.body());
                }
            }
            if (rawBody.isNull() && "running".equals(testCase.get("run").asText())) {
                assertThat(status(runId)).isEqualTo("CANCELING");
            }
            if (!rawBody.isNull() && !"missing".equals(testCase.get("run").asText())) {
                // body 거부는 아무것도 바꾸지 않는다
                assertThat(status(runId)).as(name).isIn("RUNNING", "PENDING");
            }
        }
        // 없는 run·타인 run은 같은 404 body(존재 비노출)
        assertThat(notFoundBodies).hasSize(2);
        assertThat(notFoundBodies.get(0)).isEqualTo(notFoundBodies.get(1));
    }

    @Test
    @DisplayName("대문자·형식 다른 runId·삭제 중 Note의 run·인증 없음은 상태를 바꾸지 않는다")
    void accessEdgesDoNotTouchTheRun() throws Exception {
        long user = newUser();
        UUID runId = admit(user).runId();
        assertThat(postCancel(token(user), runId.toString().toUpperCase(), null).statusCode()).isEqualTo(404);
        assertThat(postCancel(token(user), "not-a-run", null).statusCode()).isEqualTo(404);
        HttpResponse<String> anonymous = http.send(HttpRequest.newBuilder(uri("/api/ai-runs/" + runId + "/cancel"))
                .POST(HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(status(runId)).isEqualTo("PENDING");

        jdbc.update("UPDATE notes SET status = 'DELETING', deletion_requested_at = now() WHERE note_id = ?",
                noteOf(runId));
        HttpResponse<String> deleting = postCancel(token(user), runId.toString(), null);
        assertThat(deleting.statusCode()).isEqualTo(404);
        assertThat(RAW.readTree(deleting.body()).get("code").asText()).isEqualTo("RUN4041");
        assertThat(status(runId)).isEqualTo("PENDING");
    }

    // ------------------------------------------------------------------
    // PENDING 취소
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PENDING 취소: 한 transaction에 CANCELED·message·run.canceled·보상 1회, Brain 호출 없음, Note 슬롯 즉시 해제")
    void pendingCancelIsImmediateCompensatedOnceAndNeverReachesBrain() throws Exception {
        long user = newUser();
        RunAdmission run = admit(user);
        assertThat(credits(user)).isEqualTo(DAILY_CREDITS - charged(run.requestId()));

        JsonNode result = postCancelJson(token(user), run.runId());
        assertThat(result.get("status").asText()).isEqualTo("CANCELED");
        assertThat(result.get("outcome").asText()).isEqualTo("accepted");

        Map<String, Object> row = runRow(run.runId());
        assertThat(row.get("status")).isEqualTo("CANCELED");
        assertThat(row.get("attempt")).isEqualTo(0);
        assertThat(row.get("cancel_cause")).isEqualTo("user_requested");
        assertThat(row.get("cancel_requested_from")).isEqualTo("PENDING");
        assertThat(row.get("completed_at")).isNotNull();
        assertThat(row.get("runtime_cleanup_at")).isNull();
        assertThat(messageStatus(run.assistantMessageId())).isEqualTo("CANCELED");
        assertThat(eventTypes(run.runId())).containsExactly("run.created", "run.canceled");

        // 저장 payload는 canceled-pending stream fixture의 run.canceled와 같은 모양
        JsonNode fixture = load("streams/valid/canceled-pending.json").get("frames").get(1).get("payload");
        ObjectNode expected = fixture.deepCopy();
        expected.put("assistantMessageId", run.assistantMessageId().toString());
        assertThat(eventPayload(run.runId(), "run.canceled")).isEqualTo(expected);

        assertThat(compensations(run.requestId())).containsExactly("PENDING_CANCELED");
        assertThat(credits(user)).isEqualTo(DAILY_CREDITS);
        assertThat(brain.cancelCalls(run.runId().toString())).isEmpty();
        assertThat(brain.streamCalls(run.runId().toString())).isZero();

        JsonNode status = getJson(token(user), "/api/ai-runs/" + run.runId());
        assertThat(status.at("/result/status").asText()).isEqualTo("CANCELED");
        assertThat(status.at("/result/cancelable").asBoolean()).isFalse();

        // 중복 cancel은 멱등이고 다시 보상하지 않는다
        JsonNode again = postCancelJson(token(user), run.runId());
        assertThat(again.get("outcome").asText()).isEqualTo("already_terminal");
        assertThat(again.get("status").asText()).isEqualTo("CANCELED");
        assertThat(compensations(run.requestId())).hasSize(1);

        // Active Run 자리가 풀려 같은 Note에 새 요청을 받는다. 취소된 run은 자동 재실행되지 않는다.
        RunAdmission next = admit(user, String.valueOf(run.noteId()));
        assertThat(next.runId()).isNotEqualTo(run.runId());
        assertThat(status(run.runId())).isEqualTo("CANCELED");
    }

    @Test
    @DisplayName("같은 PENDING run에 동시 cancel 6건: accepted 1·already_terminal 5, 보상·run.canceled 하나")
    void concurrentPendingCancelsSettleOnce() throws Exception {
        long user = newUser();
        RunAdmission run = admit(user);
        List<String> outcomes = race(6, i -> () -> cancels.cancel(user, run.runId().toString(), null).outcome()
                .wireValue());
        assertThat(outcomes).containsOnly("accepted", "already_terminal");
        assertThat(outcomes.stream().filter("accepted"::equals)).hasSize(1);
        assertThat(eventTypes(run.runId())).containsExactly("run.created", "run.canceled");
        assertThat(compensations(run.requestId())).containsExactly("PENDING_CANCELED");
        assertThat(credits(user)).isEqualTo(DAILY_CREDITS);
    }

    @Test
    @DisplayName("PENDING 취소와 coordinator claim 경합: 하나만 이긴다 — 취소가 이기면 미전달·보상, claim이 이기면 CANCELING·무보상")
    void pendingCancelRacesClaimAndExactlyOneWins() throws Exception {
        RunClaimStore claims = new RunClaimStore(jdbc, tx);
        String owner = "t184-claimer";
        for (int round = 0; round < 8; round++) {
            failOtherPendingRuns();
            long user = newUser();
            RunAdmission run = admit(user);
            brain.mode(run.runId().toString(), CancelMode.NEVER_CONFIRM);
            assertThat(claims.acquireCoordinatorLease(owner, Duration.ofSeconds(30))).isTrue();
            String fence = "fence-race-" + round;
            List<Object> results = race(2, i -> i == 0
                    ? () -> claims.claimNext(owner, fence, Duration.ofSeconds(30), Duration.ZERO)
                    : () -> cancels.cancel(user, run.runId().toString(), null));
            @SuppressWarnings("unchecked")
            Optional<ClaimedRun> claimed = (Optional<ClaimedRun>) results.get(0);
            String status = status(run.runId());
            if (claimed.isPresent()) {
                assertThat(claimed.get().runId()).isEqualTo(run.runId());
                // claim 뒤 취소: 실행 중 취소 경로, 차감 유지
                assertThat(status).isIn("CANCELING", "CANCELED");
                assertThat(runRow(run.runId()).get("cancel_requested_from")).isEqualTo("RUNNING");
                assertThat(compensations(run.requestId())).isEmpty();
                assertThat(credits(user)).isEqualTo(DAILY_CREDITS - charged(run.requestId()));
            } else {
                assertThat(status).isEqualTo("CANCELED");
                assertThat(runRow(run.runId()).get("attempt")).isEqualTo(0);
                assertThat(compensations(run.requestId())).containsExactly("PENDING_CANCELED");
                assertThat(credits(user)).isEqualTo(DAILY_CREDITS);
            }
            claims.releaseCoordinatorLease(owner);
        }
        failOtherPendingRuns();
    }

    @Test
    @DisplayName("PENDING 취소와 deadline 만료·Server 실패 경합: terminal 하나, 보상은 request당 한 번")
    void pendingCancelRacesDeadlineAndServerFailureCompensatingOnce() throws Exception {
        for (int round = 0; round < 6; round++) {
            long user = newUser();
            boolean deadlineRace = round % 2 == 0;
            UUID runId = insertPendingRun(user, Instant.now().plusMillis(deadlineRace ? 300 : 600_000));
            if (deadlineRace) {
                Thread.sleep(400);
            }
            List<Object> results = race(2, i -> i == 0
                    ? () -> deadlineRace ? terminals.expireDeadline(runId)
                            : terminals.failByServer(runId, 0, null, RunFailureCode.INTERNAL_ERROR, true)
                    : () -> cancels.cancel(user, runId.toString(), null));
            TerminalSettlement settlement = (TerminalSettlement) results.get(0);
            String requestId = (String) runRow(runId).get("request_id");
            String status = status(runId);
            if (settlement.outcome() == Outcome.COMMITTED) {
                assertThat(status).isEqualTo("FAILED");
                assertThat(compensations(requestId)).containsExactly("INFRASTRUCTURE_FAILURE");
            } else {
                assertThat(settlement.outcome()).isEqualTo(Outcome.NOT_ACCEPTING);
                assertThat(status).isEqualTo("CANCELED");
                assertThat(compensations(requestId)).containsExactly("PENDING_CANCELED");
            }
            assertThat(eventTypes(runId)).hasSize(1);
            assertThat(credits(user)).isEqualTo(DAILY_CREDITS);
        }
    }

    // ------------------------------------------------------------------
    // RUNNING/RETRYING 취소
    // ------------------------------------------------------------------

    @Test
    @DisplayName("RUNNING 취소: CANCELING 동안 늦은 terminal·Activity·deadline 차단, 새 run·슬롯 유지 → Brain 정리 확인 뒤 CANCELED, 차감 유지")
    void runningCancelFencesLateResultsAndHoldsSlotsUntilBrainConfirms() throws Exception {
        long user = newUser();
        UUID runId = runningRun(user, CancelMode.NEVER_CONFIRM);
        RunAdmission admitted = admissionOf(runId);
        jdbc.update("UPDATE ai_runs SET status = 'RETRYING', retry_count = 1 WHERE run_id = ?", runId);

        JsonNode result = postCancelJson(token(user), runId);
        assertThat(result.get("status").asText()).isEqualTo("CANCELING");
        assertThat(result.get("outcome").asText()).isEqualTo("accepted");
        Map<String, Object> row = runRow(runId);
        assertThat(row.get("cancel_requested_from")).isEqualTo("RETRYING");
        assertThat(row.get("lease_owner")).isEqualTo("t184-worker");
        assertThat(eventTypes(runId)).containsExactly("run.created", "activity.started", "run.canceling");
        ObjectNode canceling = mapper.createObjectNode().put("assistantMessageId", admitted.assistantMessageId().toString())
                .put("status", "CANCELING");
        assertThat(eventPayload(runId, "run.canceling")).isEqualTo(canceling);
        assertThat(messageStatus(admitted.assistantMessageId())).isEqualTo("PENDING");

        // 명시적 Brain cancel: 현재 attempt·fence·cause
        await(() -> !brain.cancelCalls(runId.toString()).isEmpty());
        JsonNode call = brain.cancelCalls(runId.toString()).get(0);
        assertThat(call.get("attempt").asInt()).isEqualTo(1);
        assertThat(call.get("fence_token").asText()).isEqualTo(FENCE);
        assertThat(call.get("cause").asText()).isEqualTo("user_requested");

        // 늦은 결과 차단 (1.80·1.82 규칙, 이 run에서 실제로)
        assertThat(terminals.settle(clarificationEvent(runId)).outcome()).isEqualTo(Outcome.NOT_ACCEPTING);
        assertThat(terminals.failByServer(runId, 1, FENCE, RunFailureCode.INTERNAL_ERROR, true).outcome())
                .isEqualTo(Outcome.NOT_ACCEPTING);
        assertThat(terminals.expireDeadline(runId).outcome()).isEqualTo(Outcome.NOT_ACCEPTING);
        assertThat(events.appendActivity(activity(runId, "activity-2", "activity.started")).outcome())
                .isEqualTo(ActivityAppendResult.Outcome.NOT_ACCEPTING);
        assertThat(events.appendActivity(activity(runId, "activity-1", "activity.canceled")).outcome())
                .isEqualTo(ActivityAppendResult.Outcome.APPENDED);

        // 정리 확인 전: 상태 CANCELING, 같은 Note 새 요청 거부, 전역 슬롯 계산에 포함
        Thread.sleep(800);
        assertThat(status(runId)).isEqualTo("CANCELING");
        assertThatThrownBy(() -> admit(user, String.valueOf(admitted.noteId())))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.RUN4091));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ai_runs WHERE run_id = ? AND status IN ('RUNNING', 'RETRYING', 'CANCELING')
                  AND attempt >= 1""", Long.class, runId)).isEqualTo(1);
        JsonNode status = getJson(token(user), "/api/ai-runs/" + runId);
        assertThat(status.at("/result/status").asText()).isEqualTo("CANCELING");
        assertThat(status.at("/result/cancelable").asBoolean()).isFalse();
        assertThat(postCancelJson(token(user), runId).get("outcome").asText()).isEqualTo("already_canceling");

        // Brain이 정리를 끝내면 다음 확인에서 CANCELED
        brain.mode(runId.toString(), CancelMode.CONFIRM);
        awaitStatus(runId, "CANCELED");
        row = runRow(runId);
        assertThat(row.get("lease_owner")).isNull();
        assertThat(row.get("runtime_cleanup_at")).isNotNull();
        assertThat(Duration.between(instant(row.get("cancel_requested_at")), instant(row.get("completed_at"))))
                .isLessThan(CLEANUP_TIMEOUT);
        assertThat(messageStatus(admitted.assistantMessageId())).isEqualTo("CANCELED");
        assertThat(eventTypes(runId)).containsExactly("run.created", "activity.started", "run.canceling",
                "activity.canceled", "run.canceled");
        JsonNode canceled = eventPayload(runId, "run.canceled");
        assertThat(canceled.get("cause").asText()).isEqualTo("user_requested");
        assertThat(canceled.get("status").asText()).isEqualTo("CANCELED");
        // RUNNING 이후 사용자 취소는 차감 유지(순사용량 1)
        assertThat(compensations(admitted.requestId())).isEmpty();
        assertThat(credits(user)).isEqualTo(DAILY_CREDITS - charged(admitted.requestId()));

        // 정리 뒤 같은 Note 새 요청 가능. 취소된 run은 다시 실행되지 않는다.
        admit(user, String.valueOf(admitted.noteId()));
        assertThat(brain.streamCalls(runId.toString())).isZero();
        assertThat(postCancelJson(token(user), runId).get("outcome").asText()).isEqualTo("already_terminal");
    }

    @Test
    @DisplayName("Brain 정리 미확인·Brain 장애: cleanup-timeout까지 CANCELING을 유지하고 그 뒤 CANCELED로 확정")
    void cleanupTimeoutConfirmsWithoutBrainAcknowledgement() throws Exception {
        long first = newUser();
        long second = newUser();
        UUID neverConfirms = runningRun(first, CancelMode.NEVER_CONFIRM);
        UUID unavailable = runningRun(second, CancelMode.UNAVAILABLE);
        cancels.cancel(first, neverConfirms.toString(), null);
        cancels.cancel(second, unavailable.toString(), null);

        Thread.sleep(CLEANUP_TIMEOUT.toMillis() - 1500);
        assertThat(status(neverConfirms)).isEqualTo("CANCELING");
        assertThat(status(unavailable)).isEqualTo("CANCELING");
        assertThat(brain.cancelCalls(neverConfirms.toString()).size()).isGreaterThan(3);

        awaitStatus(neverConfirms, "CANCELED");
        awaitStatus(unavailable, "CANCELED");
        for (UUID runId : List.of(neverConfirms, unavailable)) {
            Map<String, Object> row = runRow(runId);
            assertThat(Duration.between(instant(row.get("cancel_requested_at")), instant(row.get("completed_at"))))
                    .isGreaterThanOrEqualTo(CLEANUP_TIMEOUT);
            assertThat(eventTypes(runId)).endsWith("run.canceling", "run.canceled");
            assertThat(compensations((String) row.get("request_id"))).isEmpty();
        }
    }

    @Test
    @DisplayName("Brain record absent: run lease가 살아 있는 동안(worker가 아직 보낼 수 있음) 기다리고, lease 만료 뒤 확정")
    void absentBrainRecordWaitsForTheRunLease() throws Exception {
        long user = newUser();
        UUID runId = runningRun(user, CancelMode.ABSENT);
        cancels.cancel(user, runId.toString(), null);
        Thread.sleep(1200);
        assertThat(status(runId)).isEqualTo("CANCELING");
        assertThat(brain.cancelCalls(runId.toString())).isNotEmpty();

        jdbc.update("UPDATE ai_runs SET lease_expires_at = now() WHERE run_id = ?", runId);
        awaitStatus(runId, "CANCELED");
        Map<String, Object> row = runRow(runId);
        assertThat(Duration.between(instant(row.get("cancel_requested_at")), instant(row.get("completed_at"))))
                .isLessThan(CLEANUP_TIMEOUT);
    }

    @Test
    @DisplayName("RUNNING 완료와 취소 경합: 결과 확정과 취소 중 하나만, 순사용량은 승자 정책대로")
    void runningCompletionRacesCancel() throws Exception {
        for (int round = 0; round < 6; round++) {
            long user = newUser();
            UUID runId = runningRun(user, CancelMode.CONFIRM);
            RunAdmission admitted = admissionOf(runId);
            List<Object> results = race(2, i -> i == 0
                    ? () -> terminals.settle(clarificationEvent(runId))
                    : () -> cancels.cancel(user, runId.toString(), null));
            TerminalSettlement settlement = (TerminalSettlement) results.get(0);
            if (settlement.outcome() == Outcome.COMMITTED) {
                assertThat(status(runId)).isEqualTo("COMPLETED");
                assertThat(((RunCancelResult) results.get(1)).outcome().wireValue())
                        .isEqualTo("already_terminal");
                assertThat(eventTypes(runId)).doesNotContain("run.canceling", "run.canceled");
                // clarification은 보상(순사용량 0)
                assertThat(compensations(admitted.requestId())).containsExactly("CLARIFICATION_ONLY");
                assertThat(credits(user)).isEqualTo(DAILY_CREDITS);
            } else {
                assertThat(settlement.outcome()).isEqualTo(Outcome.NOT_ACCEPTING);
                awaitStatus(runId, "CANCELED");
                assertThat(eventTypes(runId)).doesNotContain("run.completed", "response.page.started");
                assertThat(compensations(admitted.requestId())).isEmpty();
                assertThat(credits(user)).isEqualTo(DAILY_CREDITS - charged(admitted.requestId()));
            }
        }
    }

    @Test
    @DisplayName("RUNNING deadline 만료와 취소 경합: timeout 실패(보상)와 취소(무보상) 중 하나만")
    void runningDeadlineRacesCancel() throws Exception {
        for (int round = 0; round < 6; round++) {
            long user = newUser();
            UUID runId = insertPendingRun(user, Instant.now().plusMillis(400));
            claim(runId);
            brain.executing(runId.toString(), 1, FENCE);
            Thread.sleep(500);
            List<Object> results = race(2, i -> i == 0
                    ? () -> terminals.expireDeadline(runId)
                    : () -> cancels.cancel(user, runId.toString(), null));
            TerminalSettlement settlement = (TerminalSettlement) results.get(0);
            String requestId = (String) runRow(runId).get("request_id");
            if (settlement.outcome() == Outcome.COMMITTED) {
                assertThat(status(runId)).isEqualTo("FAILED");
                assertThat(runRow(runId).get("failure_code")).isEqualTo("timeout");
                assertThat(compensations(requestId)).containsExactly("INFRASTRUCTURE_FAILURE");
                assertThat(credits(user)).isEqualTo(DAILY_CREDITS);
            } else {
                assertThat(settlement.outcome()).isEqualTo(Outcome.NOT_ACCEPTING);
                // deadline이 지나도 CANCELING은 취소 경로가 끝낸다
                awaitStatus(runId, "CANCELED");
                assertThat(compensations(requestId)).isEmpty();
                assertThat(credits(user)).isEqualTo(DAILY_CREDITS - charged(requestId));
            }
        }
    }

    // ------------------------------------------------------------------
    // 단절 ≠ 취소, 반복 요청 제한
    // ------------------------------------------------------------------

    @Test
    @DisplayName("브라우저 SSE 단절은 취소가 아니다: run 상태·cancel 필드·Brain cancel 호출 없음")
    void browserDisconnectIsNotCancel() throws Exception {
        long user = newUser();
        UUID runId = runningRun(user, CancelMode.CONFIRM);
        HttpResponse<InputStream> stream = http.send(HttpRequest.newBuilder(uri("/api/ai-runs/" + runId + "/events"))
                .header("Authorization", "Bearer " + token(user)).header("Accept", "text/event-stream").GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(stream.statusCode()).isEqualTo(200);
        try (InputStream body = stream.body()) {
            assertThat(body.read(new byte[64])).isPositive();
        }
        Thread.sleep(1000);
        Map<String, Object> row = runRow(runId);
        assertThat(row.get("status")).isEqualTo("RUNNING");
        assertThat(row.get("cancel_requested_at")).isNull();
        assertThat(brain.cancelCalls(runId.toString())).isEmpty();
    }

    @Test
    @DisplayName("반복 요청 제한(1.79 RUN4291 재사용): 새 requestId·취소 뒤 재요청도 세고, 같은 requestId 재전송과 다른 사용자는 막지 않는다")
    void repeatedRequestLimitIsNotBypassedByNewRequestIdsOrCancellation() throws Exception {
        long user = newUser();
        long other = newUser();
        List<RunAdmission> runs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            RunAdmission run = admit(user);
            runs.add(run);
            // 실패·취소처럼 순사용량 0으로 끝난 요청도 제한 창에 남는다
            assertThat(cancels.cancel(user, run.runId().toString(), null).outcome().wireValue()).isEqualTo("accepted");
        }
        assertThat(credits(user)).isEqualTo(DAILY_CREDITS);
        assertThatThrownBy(() -> admit(user))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.RUN4291));
        // 같은 requestId·같은 body 재조회는 새 실행이 아니라 기존 결과(제한 대상 아님)
        RunAdmission last = runs.get(2);
        RunAdmission replayed = admission.admit(user, requestBody(last.requestId(), null, textOf(last)));
        assertThat(replayed.outcome()).isEqualTo(RunAdmission.Outcome.REPLAYED);
        assertThat(replayed.runId()).isEqualTo(last.runId());
        // 다른 사용자는 영향 없음
        assertThat(admit(other).outcome()).isEqualTo(RunAdmission.Outcome.CREATED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_runs WHERE user_id = ?", Long.class, user))
                .isEqualTo(3);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private final Map<UUID, RunAdmission> admissions = new ConcurrentHashMap<>();
    private final Map<UUID, String> texts = new ConcurrentHashMap<>();

    private RunAdmission admit(long user) {
        return admit(user, null);
    }

    private RunAdmission admit(long user, String noteId) {
        String text = "x^2-5x+6=0을 풀어줘 " + UUID.randomUUID();
        RunAdmission run = admission.admit(user, requestBody(UUID.randomUUID().toString(), noteId, text));
        admissions.put(run.runId(), run);
        texts.put(run.runId(), text);
        return run;
    }

    private RunAdmission admissionOf(UUID runId) {
        return admissions.get(runId);
    }

    private String textOf(RunAdmission run) {
        return texts.get(run.runId());
    }

    private ObjectNode requestBody(String requestId, String noteId, String text) {
        ObjectNode body = mapper.createObjectNode();
        body.put("requestId", requestId);
        body.put("text", text);
        if (noteId != null) {
            body.put("noteId", noteId);
        }
        return body;
    }

    /** 접수한 run을 coordinator claim처럼 RUNNING으로 만들고 Activity 하나를 저장한다. Brain record는 실행 중. */
    private UUID runningRun(long user, CancelMode mode) {
        UUID runId = admit(user).runId();
        brain.mode(runId.toString(), mode);
        claim(runId);
        brain.executing(runId.toString(), 1, FENCE);
        ActivityAppendResult started = events.appendActivity(activity(runId, "activity-1", "activity.started"));
        assertThat(started.outcome()).isEqualTo(ActivityAppendResult.Outcome.APPENDED);
        return runId;
    }

    private UUID completedRun(long user) {
        UUID runId = admit(user).runId();
        claim(runId);
        assertThat(terminals.settle(clarificationEvent(runId)).outcome()).isEqualTo(Outcome.COMMITTED);
        return runId;
    }

    private void claim(UUID runId) {
        jdbc.update("""
                UPDATE ai_runs SET status = 'RUNNING', attempt = 1, fence_token = ?, started_at = now(),
                       lease_owner = 't184-worker', lease_expires_at = now() + interval '1 minute', heartbeat_at = now()
                WHERE run_id = ? AND status = 'PENDING'
                """, FENCE, runId);
    }

    /** claim 경합 테스트가 다른 테스트의 PENDING run을 집지 않게 미리 끝낸다. */
    private void failOtherPendingRuns() {
        for (UUID pending : jdbc.queryForList("SELECT run_id FROM ai_runs WHERE status = 'PENDING'", UUID.class)) {
            terminals.failByServer(pending, 0, null, RunFailureCode.INTERNAL_ERROR, true);
        }
    }

    private SourceEvent activity(UUID runId, String activityId, String type) {
        ObjectNode envelope = (ObjectNode) load("../activity-v1/valid/input-analysis-started.json").deepCopy();
        envelope.put("run_id", runId.toString()).put("attempt", 1).put("fence_token", FENCE)
                .put("source_event_id", runId + ":" + activityId + ":" + type)
                .put("occurred_at", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString())
                .put("type", type);
        ObjectNode payload = (ObjectNode) envelope.get("payload");
        payload.put("activity_id", activityId);
        if (!"activity.started".equals(type)) {
            payload.remove("label");
            payload.remove("response_page_id");
        }
        return InternalRunWire.read(envelope, SourceEvent.class);
    }

    private SourceEvent clarificationEvent(UUID runId) {
        ObjectNode source = (ObjectNode) load("../terminal-v2/pairs/clarification.json").get("source").deepCopy();
        source.put("run_id", runId.toString()).put("attempt", 1).put("fence_token", FENCE)
                .put("source_event_id", runId + ":terminal");
        return InternalRunWire.read(source, SourceEvent.class);
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
                VALUES (?, ?, ?, now() + interval '1 day', 0, 0, now(), now())
                """, user, DAILY_CREDITS, DAILY_CREDITS);
        return user;
    }

    /** 접수가 남기는 모양의 PENDING run·최초 차감(deadline을 짧게 잡는 경합 검사용). */
    private UUID insertPendingRun(long user, Instant deadline) {
        return tx.execute(status -> {
            long note = jdbc.queryForObject("""
                    INSERT INTO notes (user_id, title, created_at, updated_at) VALUES (?, 't184', now(), now())
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
        });
    }

    private Map<String, Object> runRow(UUID runId) {
        return jdbc.queryForMap("SELECT * FROM ai_runs WHERE run_id = ?", runId);
    }

    private String status(UUID runId) {
        return jdbc.queryForObject("SELECT status FROM ai_runs WHERE run_id = ?", String.class, runId);
    }

    private long noteOf(UUID runId) {
        return jdbc.queryForObject("SELECT note_id FROM ai_runs WHERE run_id = ?", Long.class, runId);
    }

    private String messageStatus(UUID messageId) {
        return jdbc.queryForObject("SELECT status FROM chat_messages WHERE message_id = ?", String.class, messageId);
    }

    private List<String> eventTypes(UUID runId) {
        return jdbc.queryForList("SELECT event_type FROM ai_run_events WHERE run_id = ? ORDER BY seq", String.class,
                runId);
    }

    private JsonNode eventPayload(UUID runId, String type) throws Exception {
        return RAW.readTree(jdbc.queryForObject(
                "SELECT payload::text FROM ai_run_events WHERE run_id = ? AND event_type = ?", String.class, runId,
                type));
    }

    private List<String> compensations(String requestId) {
        return jdbc.queryForList("SELECT reason FROM request_usage_compensations WHERE request_id = ?", String.class,
                requestId);
    }

    /** 접수가 차감한 credit(플랜·요청 단위에 따라 1이 아닐 수 있다). */
    private long charged(String requestId) {
        return jdbc.queryForObject("SELECT credit_amount FROM request_usage_charges WHERE request_id = ?", Long.class,
                requestId);
    }

    private long credits(long user) {
        return jdbc.queryForObject("SELECT daily_free_credit + free_credit + paid_credit FROM credit_balance "
                + "WHERE user_id = ?", Long.class, user);
    }

    private static Instant instant(Object timestamp) {
        return timestamp instanceof OffsetDateTime odt ? odt.toInstant() : ((java.sql.Timestamp) timestamp).toInstant();
    }

    private void awaitStatus(UUID runId, String expected) {
        await(() -> expected.equals(status(runId)));
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

    private String token(long user) {
        return tokens.generateTokens(user).accessToken();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpResponse<String> postCancel(String token, String runId, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/api/ai-runs/" + runId + "/cancel"))
                .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(10));
        if (body == null) {
            request.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode postCancelJson(String token, UUID runId) throws Exception {
        HttpResponse<String> response = postCancel(token, runId.toString(), null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return RAW.readTree(response.body()).get("result");
    }

    private JsonNode getJson(String token, String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path + " " + response.body()).isEqualTo(200);
        return RAW.readTree(response.body());
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
