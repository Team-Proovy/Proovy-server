package com.proovy.integration.brain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proovy.domain.auth.service.AccessTokenBlacklistService;
import com.proovy.domain.auth.service.JwtTokenProvider;
import com.proovy.domain.run.coordinator.RunCoordinator;
import com.proovy.support.db.PostgresTestDatabase;
import com.proovy.support.http.BrowserRunHttp;
import com.proovy.support.http.SseClient;
import com.proovy.support.http.SseClient.Block;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * Server ↔ 실제 Python Brain 로컬 연동 인수 (task 1.87 단계 4, spec 07 Verification 1~5).
 *
 * <p>실제 Postgres 하나에 빈 제품 baseline(proovy_server가 Flyway 실행)과 Brain schema(brain_migration·brain_runtime,
 * Brain {@code db bootstrap}·{@code migrate})를 같이 두고 운영 role 경계 스크립트를 적용한다. Server는 production bean 그대로
 * (실제 HTTP 브라우저 API·접수·coordinator·{@code BrainRunClient}·terminal transaction·SSE)이고 Brain은 proovy-brain
 * {@code testkit}이 띄운 실제 내부 API·run driver·Postgres runtime·그래프다. 모델·Daytona는 offline fake seat이고
 * {@code no_match_notice}·{@code out_of_scope_source}·{@code rewrite_terminal}은 testkit의 scripted 결과다 — 수학 품질은 인증하지 않는다.
 *
 * <p>Brain testkit이 없으면 {@link BrainTestkit#SKIP_REASON}으로 skip한다(Server CI는 사용자 결정으로 skip).
 */
@Tag("postgres")
@Tag("brain-integration")
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
        "proovy.run.admission.rate-limit.max-requests=10000",
        "proovy.run.snapshot.request-timeout=40s",
        "proovy.run.sse.heartbeat=1s",
        "proovy.brain.auth.mode=shared-token",
        // Brain keep-alive(10s)보다 길게 — 정상 hold는 끊지 않는다
        "proovy.brain.stream-idle-timeout=15s",
        "proovy.brain.request-timeout=10s",
        "proovy.run.coordinator.enabled=true",
        "proovy.run.coordinator.instance-id=t187-it",
        "proovy.run.coordinator.max-concurrent-runs=3",
        "proovy.run.coordinator.poll-interval=100ms",
        "proovy.run.coordinator.heartbeat-interval=300ms",
        "proovy.run.coordinator.lease-ttl=2s",
        "proovy.run.coordinator.deadline-sweep-interval=200ms",
        "proovy.run.coordinator.min-dispatch-remaining=0s",
        "proovy.run.coordinator.redelivery.max-resends=4",
        "proovy.run.coordinator.redelivery.max-status-checks=8",
        "proovy.run.coordinator.redelivery.initial-backoff=200ms",
        "proovy.run.coordinator.redelivery.max-backoff=1s",
        "proovy.run.coordinator.recovery.interval=300ms",
        "proovy.run.coordinator.recovery.max-recoveries=3",
        "proovy.run.coordinator.shutdown-timeout=3s",
        "proovy.run.cancel.sweep-interval=300ms",
        "proovy.run.cancel.cleanup-timeout=20s"
})
class ServerBrainIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final long DAILY_CREDITS = 100;
    private static final String SERVER_ROLE = "proovy_server";
    private static final String SERVER_PASSWORD = "t187-server-dev-only";
    private static final String PERMISSION_DENIED = "42501";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path PINS = Path.of("src/test/resources/contracts/brain");

    private static PostgresTestDatabase db;
    private static BrainTestkit brain;

    @BeforeAll
    static void provision() throws SQLException {
        PostgresTestDatabase.requireConfigured();
        brain = BrainTestkit.fromEnvironment();
        PostgresTestDatabase.executeAsAdminOnServer("""
                DO $$ BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%1$s') THEN
                        CREATE ROLE %1$s LOGIN PASSWORD '%2$s';
                    ELSE
                        ALTER ROLE %1$s LOGIN PASSWORD '%2$s';
                    END IF;
                END $$;
                """.formatted(SERVER_ROLE, SERVER_PASSWORD));
        db = PostgresTestDatabase.createEmpty();
        try (Connection admin = db.connect(); Statement st = admin.createStatement()) {
            st.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            st.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + SERVER_ROLE);
        }
        // 빈 제품 baseline을 Server role로, 같은 database에 Brain schema를 Brain bootstrap으로 만든다
        db.migrate(SERVER_ROLE, SERVER_PASSWORD);
        brain.start(db.name(), 3);
        db.runScript("db/provisioning/role-boundary.sql");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> db.jdbcUrl());
        registry.add("spring.datasource.username", () -> SERVER_ROLE);
        registry.add("spring.datasource.password", () -> SERVER_PASSWORD);
        registry.add("proovy.brain.base-url", () -> brain.apiUrl());
        registry.add("proovy.brain.auth.shared-token", () -> brain.sharedToken());
    }

    @AfterAll
    static void tearDown() {
        if (brain != null) {
            try {
                brain.stop();
            } catch (RuntimeException ignored) {
                // supervisor가 이미 내려갔으면 정리할 child도 없다
            }
        }
        if (db != null) {
            db.close();
        }
    }

    @LocalServerPort int port;
    @MockitoBean AccessTokenBlacklistService blacklist;
    @Autowired JwtTokenProvider tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired RunCoordinator coordinator;

    private BrowserRunHttp browser;

    @BeforeEach
    void client() {
        browser = new BrowserRunHttp(port, WAIT);
    }

    // ==================================================================
    // V1 — 빈 DB 접수·중복·같은 Note 충돌
    // ==================================================================

    @Test
    @DisplayName("V1·V2: 빈 baseline 첫 텍스트 질문이 실제 Brain을 거쳐 저장되고 POST live와 GET replay가 같다")
    void firstTextQuestionCompletesThroughRealBrainAndReplaysIdentically() throws Exception {
        User user = newUser();
        String requestId = requestId();

        Run run = post(user, body(requestId, null, "x^2 - 5x + 6 = 0을 풀어줘"));

        assertThat(run.status).isEqualTo(200);
        assertThat(run.types()).startsWith("run.created").endsWith("run.completed");
        assertThat(run.frames.stream().map(Block::seq).toList())
                .isEqualTo(LongStream.rangeClosed(1, run.frames.size()).boxed().toList());
        assertThat(run.types()).contains("activity.started", "response.page.started", "response.page.completed");
        awaitStatus(run.runId, "COMPLETED");
        assertThat(row(run.runId).get("response_disposition")).isEqualTo("substantive");
        assertThat(netUsage(requestId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notes WHERE user_id = ?", user.id)).isOne();
        assertThat(count("SELECT count(*) FROM verified_solutions WHERE source_run_id = ?", run.runId)).isOne();
        assertThat(singleTerminal(run.runId)).isEqualTo("run.completed");

        try (SseClient replay = browser.events(user.token, run.runId, null)) {
            assertThat(replay.framesUntilEnd().stream().map(Block::raw).toList())
                    .containsExactlyElementsOf(run.frames.stream().map(Block::raw).toList());
        }
        JsonNode page = browser.conversation(user.token, run.noteId).body();
        assertThat(page.at("/result/messages")).hasSize(2);
        assertThat(page.at("/result/messages/1/status").asText()).isEqualTo("COMPLETED");

        // Brain은 graph를 한 번 실행했고, 첫 전달 = attempt 1, cleanup이 예약되었다
        assertThat(brain.executions(requestId)).hasSize(1);
        assertThat(streams(requestId)).allSatisfy(o -> assertThat(o.get("attempt").asInt()).isEqualTo(1));
        await(() -> row(run.runId).get("runtime_cleanup_at") != null);
    }

    @Test
    @DisplayName("V1: noteId 없는 같은 requestId 동시 전송은 Note·run·차감·Brain 실행을 하나만 남긴다")
    void concurrentDuplicateNotelessRequestsLeaveOneOfEverything() throws Exception {
        User user = newUser();
        String requestId = requestId();
        String body = body(requestId, null, "중복 전송 문제를 풀어줘");

        List<Run> runs = concurrently(5, () -> post(user, body));

        assertThat(runs).allSatisfy(run -> assertThat(run.status).isEqualTo(200));
        assertThat(runs.stream().map(run -> run.runId).distinct()).hasSize(1);
        UUID runId = runs.get(0).runId;
        awaitStatus(runId, "COMPLETED");
        assertThat(count("SELECT count(*) FROM notes WHERE user_id = ?", user.id)).isOne();
        assertThat(count("SELECT count(*) FROM ai_runs WHERE request_id = ?", requestId)).isOne();
        assertThat(count("SELECT count(*) FROM chat_messages m JOIN notes n ON n.note_id = m.note_id "
                + "WHERE n.user_id = ?", user.id)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM request_usage_charges WHERE request_id = ?", requestId)).isOne();
        assertThat(brain.executions(requestId)).hasSize(1);
        assertThat(singleTerminal(runId)).isEqualTo("run.completed");
    }

    @Test
    @DisplayName("V1: 같은 Note 동시 전송은 하나만 접수되고 나머지는 activeRunId가 있는 409이며 메시지를 만들지 않는다")
    void concurrentSameNoteRequestsAdmitOneAndRejectTheRest() throws Exception {
        User user = newUser();
        Run first = post(user, body(requestId(), null, "첫 질문"));
        awaitStatus(first.runId, "COMPLETED");
        long messagesBefore = count("SELECT count(*) FROM chat_messages WHERE note_id = ?", first.noteId);

        List<String> requestIds = List.of(requestId(), requestId(), requestId(), requestId());
        for (String id : requestIds) {
            brain.plan(id, "default", BrainTestkit.faults().put("hold_at", "classification"));
        }
        List<String> bodies = new ArrayList<>();
        requestIds.forEach(id -> bodies.add(body(id, Long.toString(first.noteId), "같은 Note 동시 질문")));
        List<SseClient> opened = concurrentlyIndexed(bodies.size(), i -> browser.post(user.token, bodies.get(i)));
        try {
            List<SseClient> admitted = opened.stream().filter(sse -> sse.status == 200).toList();
            List<SseClient> rejected = opened.stream().filter(sse -> sse.status != 200).toList();
            assertThat(admitted).hasSize(1);
            assertThat(rejected).hasSize(3).allSatisfy(sse -> {
                assertThat(sse.status).isEqualTo(409);
                assertThat(sse.errorBody).contains("RUN4091").contains("activeRunId");
            });
            UUID active = UUID.fromString(admitted.get(0).nextFrame().json().get("runId").asText());
            assertThat(rejected).allSatisfy(sse -> assertThat(sse.errorBody).contains(active.toString()));
            // 거부된 요청은 메시지·run·차감을 만들지 않았다
            assertThat(count("SELECT count(*) FROM chat_messages WHERE note_id = ?", first.noteId))
                    .isEqualTo(messagesBefore + 2);
            assertThat(count("SELECT count(*) FROM ai_runs WHERE note_id = ?", first.noteId)).isEqualTo(2);
            String winner = jdbc.queryForObject("SELECT request_id FROM ai_runs WHERE run_id = ?", String.class, active);
            for (String id : requestIds) {
                long charges = count("SELECT count(*) FROM request_usage_charges WHERE request_id = ?", id);
                assertThat(charges).isEqualTo(id.equals(winner) ? 1 : 0);
            }
            requestIds.forEach(id -> brain.release(id, null));
            awaitStatus(active, "COMPLETED");
        } finally {
            requestIds.forEach(id -> brain.release(id, null));
            for (SseClient sse : opened) {
                sse.close();
            }
        }
    }

    // ==================================================================
    // V4 — 결과 분류별 canonical 결과·순사용량
    // ==================================================================

    record Outcome(String scenario, List<String> tasks, String terminal, String status, String disposition,
                   String failureCode, long netUsage, String firstBlock) {
    }

    @Test
    @DisplayName("V4: 정상·check·미검증·clarification·unsupported_only·혼합·자료 부재(scripted)·최종 실패의 결과와 순사용량")
    void outcomeMatrixMatchesCanonicalResultsAndNetUsagePolicy() throws Exception {
        List<Outcome> matrix = List.of(
                new Outcome("default", List.of("solve"), "run.completed", "COMPLETED", "substantive", null, 1, "solution"),
                new Outcome("default", List.of("check"), "run.completed", "COMPLETED", "substantive", null, 1, "check"),
                new Outcome("inconclusive", List.of(), "run.completed", "COMPLETED", "substantive", null, 1, "solution"),
                new Outcome("clarification", List.of(), "run.completed", "COMPLETED", "clarification_only", null, 0,
                        "rich_text"),
                new Outcome("unsupported_only", List.of(), "run.completed", "COMPLETED", "unsupported_only", null, 0,
                        "rich_text"),
                new Outcome("mixed", List.of(), "run.completed", "COMPLETED", "substantive", null, 1, "solution"),
                new Outcome("no_match_notice", List.of(), "run.completed", "COMPLETED", "substantive", null, 1,
                        "rich_text"),
                new Outcome("verification_failed", List.of(), "run.failed", "FAILED", null, "verification_failed", 0,
                        null),
                new Outcome("infrastructure_failure", List.of(), "run.failed", "FAILED", null, "provider_error", 0,
                        null));
        for (Outcome expected : matrix) {
            User user = newUser();
            String requestId = requestId();
            brain.plan(requestId, expected.scenario, null);

            Run run = post(user, body(requestId, null, "x^2 - 5x + 6 = 0", expected.tasks.toArray(String[]::new)));

            String label = expected.scenario + expected.tasks;
            assertThat(run.types()).as(label).endsWith(expected.terminal);
            awaitStatus(run.runId, expected.status);
            Map<String, Object> row = row(run.runId);
            assertThat(row.get("response_disposition")).as(label).isEqualTo(expected.disposition);
            assertThat(row.get("failure_code")).as(label).isEqualTo(expected.failureCode);
            assertThat(netUsage(requestId)).as(label).isEqualTo(expected.netUsage);
            assertThat(credits(user)).as(label).isEqualTo(DAILY_CREDITS - expected.netUsage * creditsPerUnit());
            assertThat(singleTerminal(run.runId)).as(label).isEqualTo(expected.terminal);
            assertThat(brain.executions(requestId)).as(label).hasSize(1);
            if (expected.firstBlock != null) {
                assertThat(responsePages(row)).as(label).contains("\"" + expected.firstBlock + "\"");
            }
            if (expected.scenario.equals("inconclusive")) {
                assertThat(count("SELECT count(*) FROM verified_solutions WHERE source_run_id = ?", run.runId))
                        .as("inconclusive publishes no Verified Solution").isZero();
            }
            if (expected.scenario.equals("mixed")) {
                assertThat(responsePages(row)).contains("\"notice\"");
            }
        }
    }

    @Test
    @DisplayName("V4: 같은 Note의 Verified Solution을 참조한 explain 후속이 재표현으로 완료되고 차감된다")
    void explainFollowUpReexpressesTheVerifiedSolutionOfTheNote() throws Exception {
        User user = newUser();
        Run solved = post(user, body(requestId(), null, "x^2 - 5x + 6 = 0을 풀어줘", "solve"));
        awaitStatus(solved.runId, "COMPLETED");
        String requestId = requestId();
        brain.plan(requestId, "explain_existing", null);

        Run explained = post(user, body(requestId, Long.toString(solved.noteId), "방금 풀이를 설명해줘", "explain"));

        awaitStatus(explained.runId, "COMPLETED");
        assertThat(row(explained.runId).get("response_disposition")).isEqualTo("substantive");
        assertThat(responsePages(row(explained.runId))).contains("\"explanation\"");
        // Brain은 Server snapshot의 verified_solution_refs를 받았다
        assertThat(streams(requestId).get(0).get("body").asText()).contains("verified_solution_refs")
                .contains(String.valueOf(jdbc.queryForObject(
                        "SELECT public_solution_id FROM verified_solutions WHERE source_run_id = ?", String.class,
                        solved.runId)));
        assertThat(netUsage(requestId)).isEqualTo(1);
    }

    // ==================================================================
    // V2 — SSE 단절·재접속·내부 SSE 유실
    // ==================================================================

    @Test
    @DisplayName("V2: 브라우저 단절 뒤 Last-Event-ID 재접속과 내부 SSE 유실 뒤 재부착이 같은 canonical 결과를 무유실로 복원한다")
    void browserDisconnectAndInternalSseLossRestoreTheSameCanonicalStream() throws Exception {
        User user = newUser();
        String requestId = requestId();
        // 내부 SSE: 첫 전달은 첫 frame(입력 분석 activity) 직전에 끊긴다. 실행은 solve에서 붙잡아 재부착을 기다린다
        brain.plan(requestId, "default", BrainTestkit.faults().put("hold_at", "solve")
                .put("drop_stream_after_frames", 0));

        List<Block> beforeDisconnect = new ArrayList<>();
        UUID runId;
        try (SseClient post = browser.post(user.token, body(requestId, null, "재접속 문제를 풀어줘"))) {
            Block created = post.nextFrame();
            beforeDisconnect.add(created);
            runId = UUID.fromString(created.json().get("runId").asText());
            beforeDisconnect.add(post.nextFrame());
        }
        // 내부 stream이 끊긴 뒤 Server가 status(executing) → 같은 요청으로 다시 붙을 때까지 실행을 붙잡아 둔다
        try {
            await(() -> streams(requestId).size() >= 2);
        } finally {
            brain.release(requestId, null);
        }
        awaitStatus(runId, "COMPLETED");

        List<Block> rest;
        try (SseClient resumed = browser.events(user.token, runId, beforeDisconnect.get(1).id())) {
            rest = resumed.framesUntilEnd();
        }
        List<Block> full;
        try (SseClient replay = browser.events(user.token, runId, null)) {
            full = replay.framesUntilEnd();
        }
        List<String> joined = new ArrayList<>(beforeDisconnect.stream().map(Block::raw).toList());
        joined.addAll(rest.stream().map(Block::raw).toList());
        assertThat(joined).containsExactlyElementsOf(full.stream().map(Block::raw).toList());
        assertThat(full.get(full.size() - 1).event()).isEqualTo("run.completed");

        // Brain: 같은 attempt·fence로 재부착했고 graph는 한 번, Server는 source event를 중복 저장하지 않았다
        List<JsonNode> streams = streams(requestId);
        assertThat(streams).hasSizeGreaterThanOrEqualTo(2);
        assertThat(streams.get(0).get("dropped").asBoolean()).isTrue();
        assertThat(brain.observations(requestId).stream().map(o -> o.get("operation").asText())).contains("status");
        assertThat(streams.stream().map(o -> o.get("attempt").asInt() + "/" + o.get("fence_token").asText()).distinct())
                .hasSize(1);
        assertThat(brain.executions(requestId)).hasSize(1);
        assertThat(count("SELECT count(*) FROM ai_run_events WHERE run_id = ? AND source_event_id IS NOT NULL", runId))
                .isEqualTo(count("SELECT count(DISTINCT source_event_id) FROM ai_run_events WHERE run_id = ?", runId));
        assertThat(singleTerminal(runId)).isEqualTo("run.completed");
    }

    @Test
    @DisplayName("V2·V3: Brain이 terminal을 저장한 뒤 전달이 끊기면 status로 저장 결과를 회수하고 재실행하지 않는다")
    void storedTerminalLostInDeliveryIsRecoveredByStatusWithoutRerun() throws Exception {
        User user = newUser();
        String requestId = requestId();
        brain.plan(requestId, "default", BrainTestkit.faults().put("drop_before_terminal", true));

        Run run = post(user, body(requestId, null, "저장 후 유실 문제를 풀어줘"));

        assertThat(run.types()).endsWith("run.completed");
        awaitStatus(run.runId, "COMPLETED");
        assertThat(brain.executions(requestId)).hasSize(1);
        assertThat(brain.observations(requestId).stream().map(o -> o.get("operation").asText()))
                .contains("stream");
        assertThat(streams(requestId).get(0).get("dropped").asBoolean()).isTrue();
        assertThat(singleTerminal(run.runId)).isEqualTo("run.completed");
        assertThat(netUsage(requestId)).isEqualTo(1);
    }

    // ==================================================================
    // 재시작·소실
    // ==================================================================

    @Test
    @DisplayName("Server 재시작: 멈춘 동안 Brain이 저장한 terminal을 재시작 뒤 같은 attempt·fence로 회수하고 graph를 다시 돌리지 않는다")
    void serverRestartRecoversTheStoredTerminalWithoutRerun() throws Exception {
        User user = newUser();
        String requestId = requestId();
        brain.plan(requestId, "default", BrainTestkit.faults().put("hold_at", "solve"));
        SseClient post = browser.post(user.token, body(requestId, null, "재시작 문제를 풀어줘"));
        UUID runId = UUID.fromString(post.nextFrame().json().get("runId").asText());
        post.close();
        await(() -> !brain.executions(requestId).isEmpty());
        Map<String, Object> before = row(runId);

        coordinator.stop();
        try {
            brain.release(requestId, null);
            await(() -> "terminal".equals(brain.internalRunStatus(runId.toString(),
                    (Integer) before.get("attempt"), (String) before.get("fence_token")).path("state").asText()));
        } finally {
            coordinator.start();
        }

        awaitStatus(runId, "COMPLETED");
        Map<String, Object> after = row(runId);
        assertThat(after.get("attempt")).isEqualTo(before.get("attempt"));
        assertThat(after.get("fence_token")).isEqualTo(before.get("fence_token"));
        assertThat((Integer) after.get("recovery_count")).isGreaterThanOrEqualTo(1);
        assertThat(brain.executions(requestId)).hasSize(1);
        assertThat(singleTerminal(runId)).isEqualTo("run.completed");
        assertThat(netUsage(requestId)).isEqualTo(1);
    }

    @Test
    @DisplayName("Brain 저장 전 소실: terminal 저장 직전 Brain을 SIGKILL하면 재시작한 Brain은 graph를 다시 돌리지 않고 Server는 한 번 보상한다")
    void brainLostBeforeStorageIsSettledAsFailureNotRerun() throws Exception {
        User user = newUser();
        String requestId = requestId();
        brain.plan(requestId, "default", BrainTestkit.faults().put("hold_terminal_write", true));
        SseClient post = browser.post(user.token, body(requestId, null, "저장 전 소실 문제를 풀어줘"));
        UUID runId = UUID.fromString(post.nextFrame().json().get("runId").asText());
        await(() -> count("SELECT count(*) FROM ai_run_events WHERE run_id = ? AND event_type = 'activity.completed'",
                runId) >= 2);
        Map<String, Object> epoch = row(runId);

        brain.restart("kill");

        // 새 Brain process: plan·실행 기록이 없고 record는 terminal 없이 executing이다(재개·재실행 없음)
        JsonNode lost = brain.internalRunStatus(runId.toString(), (Integer) epoch.get("attempt"),
                (String) epoch.get("fence_token"));
        assertThat(lost.get("state").asText()).isEqualTo("executing");
        assertThat(lost.has("terminal") && !lost.get("terminal").isNull()).isFalse();
        awaitStatus(runId, "FAILED");
        post.close();
        Map<String, Object> row = row(runId);
        assertThat(row.get("failure_class")).isEqualTo("infrastructure");
        System.err.println("[brain-integration] lost-before-storage outcome: failure_code=" + row.get("failure_code"));
        assertThat(brain.executions(requestId)).isEmpty();
        assertThat(netUsage(requestId)).isZero();
        assertThat(singleTerminal(runId)).isEqualTo("run.failed");
        assertThat(count("SELECT count(*) FROM request_usage_compensations WHERE request_id = ?", requestId)).isOne();
    }

    // ==================================================================
    // V3 — 완료·취소·deadline 경합
    // ==================================================================

    @Test
    @DisplayName("V3·V4: RUNNING 취소는 늦은 완료를 막고 CANCELED 하나만 남기며 무보상이다")
    void runningCancelBlocksTheLateCompletionAndKeepsTheCharge() throws Exception {
        User user = newUser();
        String requestId = requestId();
        brain.plan(requestId, "default", BrainTestkit.faults().put("hold_at", "solve"));
        SseClient post = browser.post(user.token, body(requestId, null, "취소할 문제"));
        UUID runId = UUID.fromString(post.nextFrame().json().get("runId").asText());
        await(() -> !brain.executions(requestId).isEmpty());
        awaitStatus(runId, "RUNNING");

        BrowserRunHttp.Response canceled = browser.cancel(user.token, runId);
        assertThat(canceled.status()).isEqualTo(200);
        assertThat(canceled.body().toString()).contains("CANCELING");
        brain.release(requestId, null);

        awaitStatus(runId, "CANCELED");
        List<Block> frames = post.framesUntilEnd();
        post.close();
        assertThat(frames.stream().map(Block::event)).contains("run.canceling", "run.canceled")
                .doesNotContain("run.completed");
        assertThat(singleTerminal(runId)).isEqualTo("run.canceled");
        assertThat(netUsage(requestId)).isEqualTo(1);
        assertThat(brain.observations(requestId)).anySatisfy(o -> {
            assertThat(o.get("operation").asText()).isEqualTo("cancel");
            assertThat(o.get("cause").asText()).isEqualTo("user_requested");
            assertThat(o.get("auth").asText()).isEqualTo("shared_token");
        });
        assertThat(count("SELECT count(*) FROM verified_solutions WHERE source_run_id = ?", runId)).isZero();
    }

    @Test
    @DisplayName("V3·V4: 슬롯 대기 PENDING 취소는 Brain을 부르지 않고 한 번 보상한다")
    void pendingCancelIsCompensatedAndNeverReachesBrain() throws Exception {
        List<String> holders = new ArrayList<>();
        List<SseClient> held = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                User owner = newUser();
                String id = requestId();
                holders.add(id);
                brain.plan(id, "default", BrainTestkit.faults().put("hold_at", "classification"));
                held.add(browser.post(owner.token, body(id, null, "슬롯 점유 " + i)));
            }
            await(() -> holders.stream().allMatch(id -> !brain.executions(id).isEmpty()));

            User user = newUser();
            String requestId = requestId();
            SseClient post = browser.post(user.token, body(requestId, null, "대기 중 취소"));
            UUID runId = UUID.fromString(post.nextFrame().json().get("runId").asText());
            assertThat(row(runId).get("status")).isEqualTo("PENDING");

            BrowserRunHttp.Response canceled = browser.cancel(user.token, runId);

            assertThat(canceled.status()).isEqualTo(200);
            awaitStatus(runId, "CANCELED");
            post.close();
            assertThat(row(runId).get("attempt")).isEqualTo(0);
            assertThat(netUsage(requestId)).isZero();
            assertThat(jdbc.queryForObject("SELECT reason FROM request_usage_compensations WHERE request_id = ?",
                    String.class, requestId)).isEqualTo("PENDING_CANCELED");
            assertThat(brain.observations(requestId)).isEmpty();
        } finally {
            holders.forEach(id -> brain.release(id, null));
            for (SseClient sse : held) {
                sse.close();
            }
        }
    }

    @Test
    @DisplayName("V3: 요청 deadline 경합(Server sweep·Brain timeout seal)은 timeout 실패 하나와 보상 한 번만 남기고 늦은 완료를 버린다")
    void deadlineRaceSettlesOnceAsTimeoutAndDropsTheLateCompletion() throws Exception {
        User user = newUser();
        String requestId = requestId();
        brain.plan(requestId, "default", BrainTestkit.faults().put("hold_at", "solve"));
        SseClient post = browser.post(user.token, body(requestId, null, "deadline 문제"));
        UUID runId = UUID.fromString(post.nextFrame().json().get("runId").asText());

        // request-timeout 40s: hold를 풀지 않고 deadline을 넘긴다
        awaitStatus(runId, Duration.ofSeconds(70), "FAILED");
        brain.release(requestId, null);
        Thread.sleep(3000);
        post.close();

        Map<String, Object> row = row(runId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("failure_code")).isEqualTo("timeout");
        System.err.println("[brain-integration] deadline race settled by "
                + (row.get("terminal_source_event_id") == null ? "server deadline sweep" : "brain timeout terminal"));
        assertThat(singleTerminal(runId)).isEqualTo("run.failed");
        assertThat(count("SELECT count(*) FROM request_usage_compensations WHERE request_id = ?", requestId)).isOne();
        assertThat(netUsage(requestId)).isZero();
        assertThat(count("SELECT count(*) FROM verified_solutions WHERE source_run_id = ?", runId)).isZero();
    }

    @Test
    @DisplayName("V3: Brain fake clock으로 Brain 쪽 deadline이 먼저 지나면 Brain timeout terminal이 하나의 실패로 확정된다")
    void brainSideDeadlineFromTheFakeClockSettlesAsTimeout() throws Exception {
        User user = newUser();
        String requestId = requestId();
        brain.plan(requestId, "default", BrainTestkit.faults().put("hold_at", "solve"));
        SseClient post = browser.post(user.token, body(requestId, null, "Brain deadline 문제"));
        UUID runId = UUID.fromString(post.nextFrame().json().get("runId").asText());
        await(() -> !brain.executions(requestId).isEmpty());

        brain.advanceClock(120);
        try {
            awaitStatus(runId, "FAILED");
        } finally {
            brain.advanceClock(-120);
            brain.release(requestId, null);
            post.close();
        }
        Map<String, Object> row = row(runId);
        assertThat(row.get("failure_code")).isEqualTo("timeout");
        // Server deadline(40s) 전에 Brain이 봉인한 timeout terminal을 받아 확정했다(Server sweep이면 source event가 없다)
        assertThat(row.get("terminal_source_event_id")).isNotNull();
        assertThat(singleTerminal(runId)).isEqualTo("run.failed");
        assertThat(netUsage(requestId)).isZero();
    }

    // ==================================================================
    // V5 — fixture·fingerprint 일치, scope/provenance 위반 거부
    // ==================================================================

    @Test
    @DisplayName("V5: Java와 Python이 실제 요청의 fingerprint에 합의하고 Brain이 쓰는 fixture가 Server pin과 바이트 단위로 같다")
    void javaAndPythonAgreeOnTheFingerprintAndPinnedFixtures() throws Exception {
        User user = newUser();
        String requestId = requestId();
        Run run = post(user, body(requestId, null, "fingerprint 문제를 풀어줘", "solve"));
        awaitStatus(run.runId, "COMPLETED");
        Map<String, Object> row = row(run.runId);

        JsonNode status = brain.internalRunStatus(run.runId.toString(), (Integer) row.get("attempt"),
                (String) row.get("fence_token"));
        assertThat(status.get("request_fingerprint").asText()).isEqualTo(row.get("request_fingerprint"));
        // Brain이 받은 요청은 Server snapshot에서 복원한 그대로다
        JsonNode delivered = JSON.readTree(streams(requestId).get(0).get("body").asText());
        assertThat(delivered.get("request_id").asText()).isEqualTo(requestId);
        assertThat(delivered.get("run_id").asText()).isEqualTo(run.runId.toString());
        assertThat(delivered.get("fence_token").asText()).isEqualTo(row.get("fence_token"));

        JsonNode brainFiles = brain.info().get("contract_files");
        int compared = 0;
        try (var pins = Files.list(PINS)) {
            for (Path pin : pins.filter(p -> p.getFileName().toString().startsWith("PIN")).toList()) {
                JsonNode doc = JSON.readTree(pin.toFile());
                if (doc.hasNonNull("manifest") && doc.hasNonNull("manifest_sha256")) {
                    assertThat(brainFiles.path(doc.get("manifest").asText()).asText()).as(pin + " manifest")
                            .isEqualTo(doc.get("manifest_sha256").asText());
                    compared++;
                }
                for (Iterator<Map.Entry<String, JsonNode>> it = doc.path("files").fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> file = it.next();
                    assertThat(brainFiles.path(file.getKey()).asText()).as(pin + " " + file.getKey())
                            .isEqualTo(file.getValue().asText());
                    compared++;
                }
            }
        }
        assertThat(compared).isGreaterThan(50);
        String expectedCommit = System.getenv("PROOVY_BRAIN_EXPECTED_COMMIT");
        if (expectedCommit != null) {
            JsonNode info = brain.info();
            assertThat(info.get("brain_commit").asText()).isEqualTo(expectedCommit);
            assertThat(info.get("brain_dirty").asBoolean()).isFalse();
        }
    }

    @Test
    @DisplayName("V5: run scope 밖 asset을 인용한 terminal(scripted)은 SOURCE_SCOPE로 거부되고 출처·Verified Solution 없이 한 번 보상된다")
    void outOfScopeSourceTerminalIsRejected() throws Exception {
        User user = newUser();
        String requestId = requestId();
        brain.plan(requestId, "out_of_scope_source", null);

        Run run = post(user, body(requestId, null, "범위 밖 출처"));

        awaitStatus(run.runId, "FAILED");
        Map<String, Object> row = row(run.runId);
        assertThat(row.get("failure_code")).isEqualTo("internal_error");
        assertThat(run.types()).doesNotContain("run.completed");
        assertThat(singleTerminal(run.runId)).isEqualTo("run.failed");
        assertThat(count("SELECT count(*) FROM verified_solutions WHERE source_run_id = ?", run.runId)).isZero();
        assertThat(String.valueOf(jdbc.queryForObject("SELECT coalesce(source_references::text, '') FROM chat_messages "
                + "WHERE message_id = ?", String.class, row.get("assistant_message_id")))).doesNotContain("note-alpha");
        assertThat(netUsage(requestId)).isZero();
        assertThat(brain.executions(requestId)).singleElement()
                .satisfies(e -> assertThat(e.get("kind").asText()).isEqualTo("scripted"));
    }

    @Test
    @DisplayName("V5·V3: 다른 run의 envelope로 변조된 terminal(scripted)은 stream·status 어디서 와도 저장되지 않는다")
    void forgedRunEnvelopeIsNeverStored() throws Exception {
        User user = newUser();
        String requestId = requestId();
        brain.plan(requestId, "default", BrainTestkit.faults()
                .put("rewrite_status", true)
                .set("rewrite_terminal", JsonNodeFactory.instance.objectNode().put("run_id", "run-someone-else")));

        Run run = post(user, body(requestId, null, "변조된 run 문제"));

        awaitStatus(run.runId, "FAILED");
        assertThat(run.types()).doesNotContain("run.completed");
        assertThat(singleTerminal(run.runId)).isEqualTo("run.failed");
        assertThat(netUsage(requestId)).isZero();
        assertThat(count("SELECT count(*) FROM verified_solutions WHERE source_run_id = ?", run.runId)).isZero();
        assertThat(brain.executions(requestId)).hasSize(1);
    }

    @Test
    @DisplayName("V3·V5: 이전 fence로 변조된 stream terminal(scripted)은 버려지고 저장된 현재 fence 결과만 한 번 확정된다")
    void staleFenceTerminalIsDroppedAndOnlyTheCurrentEpochSettles() throws Exception {
        User user = newUser();
        String requestId = requestId();
        brain.plan(requestId, "default", BrainTestkit.faults()
                .set("rewrite_terminal", JsonNodeFactory.instance.objectNode().put("fence_token", "fence-stale")));

        Run run = post(user, body(requestId, null, "이전 fence 문제"));

        // 변조 frame은 확정되지 않고, 같은 attempt·fence의 status 조회가 Brain에 저장된 현재 fence terminal을 확정한다
        // (2026-10-19 실측) — 결과는 하나, 변조값은 어디에도 저장되지 않는다
        awaitStatus(run.runId, "COMPLETED");
        assertThat(count("SELECT count(*) FROM ai_run_events WHERE run_id = ? AND payload::text LIKE '%fence-stale%'",
                run.runId)).isZero();
        assertThat(singleTerminal(run.runId)).isEqualTo("run.completed");
        assertThat(brain.observations(requestId).stream().map(o -> o.get("operation").asText())).contains("status");
        assertThat(row(run.runId).get("fence_token")).isNotEqualTo("fence-stale");
        assertThat(netUsage(requestId)).isEqualTo(1);
        assertThat(brain.executions(requestId)).hasSize(1);
    }

    // ==================================================================
    // 보안 경계
    // ==================================================================

    @Test
    @DisplayName("보안: Brain에는 서비스 token만 가고 사용자 JWT·grant·URL·내부 원문이 브라우저와 Brain 어디에도 새지 않으며 role이 격리된다")
    void securityBoundariesHold() throws Exception {
        User user = newUser();
        String requestId = requestId();
        Run run = post(user, body(requestId, null, "보안 경계 문제를 풀어줘"));
        awaitStatus(run.runId, "COMPLETED");

        // JWT 비전달: Brain이 받은 모든 호출은 shared token이고 body에 사용자 JWT·grant·URL이 없다
        List<JsonNode> calls = brain.observations(requestId);
        assertThat(calls).isNotEmpty().allSatisfy(o -> {
            assertThat(o.get("auth").asText()).isEqualTo("shared_token");
            assertThat(o.get("body").asText()).doesNotContain(user.token).doesNotContain("Bearer");
        });
        JsonNode delivered = JSON.readTree(streams(requestId).get(0).get("body").asText());
        assertThat(delivered.path("asset_access_grant").isNull() || delivered.path("asset_access_grant").isMissingNode())
                .isTrue();
        assertThat(delivered.toString()).doesNotContain("read_url").doesNotContain("X-Goog-Signature");

        // 서비스 인증 거부: token 없음·사용자 JWT·틀린 token은 Brain이 거부한다
        assertThat(brain.internalStatus(run.runId.toString(), null)).isIn(401, 403);
        assertThat(brain.internalStatus(run.runId.toString(), "Bearer " + user.token)).isIn(401, 403);
        assertThat(brain.internalStatus(run.runId.toString(), "Bearer not-the-token")).isIn(401, 403);

        // 브라우저 비노출: frame·대화 조회에 fence·grant·URL·Brain 내부 식별 필드가 없다
        String browserView = String.join("\n", run.frames.stream().map(Block::raw).toList())
                + browser.conversation(user.token, run.noteId).body();
        assertThat(browserView).doesNotContain("fence_token").doesNotContain("fenceToken")
                .doesNotContain("asset_access_grant").doesNotContain("assetAccessGrant")
                .doesNotContain("read_url").doesNotContain("readUrl").doesNotContain(brain.sharedToken());

        // 역할별 DB 격리(같은 database): brain_runtime → 제품 테이블 거부, proovy_server → Brain schema 거부
        try (Connection runtime = db.connect("brain_runtime", brain.runtimePassword());
             Statement st = runtime.createStatement()) {
            assertThatThrownBy(() -> st.executeQuery("SELECT count(*) FROM public.ai_runs"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(PERMISSION_DENIED));
            try (var rs = st.executeQuery("SELECT count(*) FROM brain_runtime.run_records")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isPositive();
            }
        }
        try (Connection server = db.connect(SERVER_ROLE, SERVER_PASSWORD); Statement st = server.createStatement()) {
            assertThatThrownBy(() -> st.executeQuery("SELECT count(*) FROM brain_runtime.run_records"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(PERMISSION_DENIED));
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    record User(long id, String token) {
    }

    static final class Run {
        final int status;
        final List<Block> frames;
        final UUID runId;
        final long noteId;

        Run(int status, List<Block> frames) {
            this.status = status;
            this.frames = frames;
            JsonNode created = frames.isEmpty() ? null : frames.get(0).json();
            this.runId = created == null ? null : UUID.fromString(created.get("runId").asText());
            this.noteId = created == null ? -1 : created.at("/payload/noteId").asLong();
        }

        List<String> types() {
            return frames.stream().map(Block::event).toList();
        }
    }

    private Run post(User user, String body) throws Exception {
        try (SseClient sse = browser.post(user.token, body)) {
            if (sse.status != 200) {
                return new Run(sse.status, List.of());
            }
            return new Run(sse.status, sse.framesUntilEnd());
        }
    }

    private static String body(String requestId, String noteId, String text, String... tasks) {
        ObjectNode body = JsonNodeFactory.instance.objectNode().put("requestId", requestId).put("text", text);
        if (noteId != null) {
            body.put("noteId", noteId);
        }
        if (tasks.length > 0) {
            ArrayNode list = body.putArray("requestedTasks");
            for (String task : tasks) {
                list.add(task);
            }
        }
        return body.toString();
    }

    private static String requestId() {
        return UUID.randomUUID().toString();
    }

    private User newUser() {
        String key = UUID.randomUUID().toString();
        long id = jdbc.queryForObject("""
                INSERT INTO users (nickname, provider, provider_user_id, email, created_at, updated_at)
                VALUES ('t187', 'KAKAO', ?, ?, now(), now()) RETURNING user_id
                """, Long.class, key, key + "@example.test");
        jdbc.update("""
                INSERT INTO credit_balance (user_id, daily_free_credit, daily_free_limit, daily_expires_at, free_credit,
                                            paid_credit, created_at, updated_at)
                VALUES (?, ?, ?, now() + interval '1 day', 0, 0, now(), now())
                """, id, DAILY_CREDITS, DAILY_CREDITS);
        return new User(id, tokens.generateTokens(id).accessToken());
    }

    private String responsePages(Map<String, Object> runRow) {
        return String.valueOf(jdbc.queryForObject("SELECT response_pages::text FROM chat_messages WHERE message_id = ?",
                String.class, runRow.get("assistant_message_id")));
    }

    private Map<String, Object> row(UUID runId) {
        return jdbc.queryForMap("SELECT * FROM ai_runs WHERE run_id = ?", runId);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    /** 차감 단위 수 − 보상 단위 수 (request당 ledger 한 줄씩). */
    private long netUsage(String requestId) {
        long charged = count("SELECT count(*) FROM request_usage_charges WHERE request_id = ?", requestId);
        long compensated = count("SELECT count(*) FROM request_usage_compensations WHERE request_id = ?", requestId);
        return charged - compensated;
    }

    private long creditsPerUnit() {
        Long amount = jdbc.queryForObject("SELECT max(credit_amount) FROM request_usage_charges", Long.class);
        return amount == null ? 0 : amount;
    }

    private long credits(User user) {
        return jdbc.queryForObject("SELECT daily_free_credit + free_credit + paid_credit FROM credit_balance "
                + "WHERE user_id = ?", Long.class, user.id);
    }

    /** terminal event가 정확히 하나이고 그 type. */
    private String singleTerminal(UUID runId) {
        List<String> terminals = jdbc.queryForList("""
                SELECT event_type FROM ai_run_events WHERE run_id = ?
                  AND event_type IN ('run.completed', 'run.failed', 'run.canceled')
                """, String.class, runId);
        assertThat(terminals).as("terminal events of " + runId).hasSize(1);
        return terminals.get(0);
    }

    private List<JsonNode> streams(String requestId) {
        return brain.observations(requestId).stream().filter(o -> o.get("operation").asText().equals("stream")).toList();
    }

    private void awaitStatus(UUID runId, String... statuses) throws InterruptedException {
        awaitStatus(runId, WAIT, statuses);
    }

    private void awaitStatus(UUID runId, Duration within, String... statuses) throws InterruptedException {
        Set<String> wanted = new HashSet<>(List.of(statuses));
        long deadline = System.nanoTime() + within.toNanos();
        String last = null;
        while (System.nanoTime() < deadline) {
            last = (String) row(runId).get("status");
            if (wanted.contains(last)) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("run " + runId + " stayed " + last + ", wanted " + wanted);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("condition not reached in " + WAIT);
    }

    private static <T> List<T> concurrently(int n, Callable<T> task) throws Exception {
        return concurrentlyIndexed(n, i -> task.call());
    }

    interface IndexedTask<T> {
        T call(int index) throws Exception;
    }

    private static <T> List<T> concurrentlyIndexed(int n, IndexedTask<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CyclicBarrier barrier = new CyclicBarrier(n);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return task.call(index);
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
