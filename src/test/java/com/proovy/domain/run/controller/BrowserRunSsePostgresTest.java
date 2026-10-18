package com.proovy.domain.run.controller;

import static com.proovy.domain.run.contract.BrowserRunContractTestSupport.load;
import static com.proovy.domain.run.contract.BrowserRunContractTestSupport.schemaErrors;
import static com.proovy.support.db.ProductRows.ALICE;
import static com.proovy.support.db.ProductRows.BOB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proovy.domain.auth.service.AccessTokenBlacklistService;
import com.proovy.domain.auth.service.JwtTokenProvider;
import com.proovy.domain.run.brain.InternalRunWire;
import com.proovy.domain.run.brain.SourceEvent;
import com.proovy.domain.run.contract.BrowserRunStreamContractTest;
import com.proovy.domain.run.event.ActivityAppendResult;
import com.proovy.domain.run.event.RunEventSignals;
import com.proovy.domain.run.event.RunEventStore;
import com.proovy.domain.run.stream.RunStreamService;
import com.proovy.domain.run.terminal.RunTerminalService;
import com.proovy.domain.run.terminal.TerminalSettlement;
import com.proovy.domain.usage.service.RequestUsageService;
import com.proovy.support.db.PostgresTestDatabase;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 실제 Postgres와 실제 HTTP SSE client(java.net.http, 임의 port)로 브라우저 run API(1.86)를 검사한다.
 * 실행 쪽은 coordinator(1.83) 대신 이 테스트가 fake producer로 흉내 낸다: claim SQL → {@code appendActivity} →
 * {@code RunTerminalService.settle}(1.82) — 모두 production 저장 경로라 commit 뒤 신호·replay가 실제와 같다.
 * <p>
 * 검사: 최초 POST live·완료 종료, 중간 단절 뒤 Last-Event-ID 재접속(방송 유실 포함), 재접속 반복 중 동시 append의 무유실·무중복,
 * 신호 전부 유실 시 DB 복구, heartbeat comment, terminal commit 전 비공개, 타인·없는 run 비노출, cursor 판정표,
 * stream 전 거부 JSON, 같은 requestId 재전송 replay, 조회·replay·live canonical 일치와 read-time availability.
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
        "proovy.run.admission.rate-limit.max-requests=10000",
        "proovy.run.sse.heartbeat=300ms",
        "proovy.run.sse.catch-up-interval=1s",
        "proovy.run.sse.batch-size=7"
})
class BrowserRunSsePostgresTest {

    private static final String FENCE = "fence-1";
    private static final String ASSET_SHA256 = "a".repeat(64);
    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final ObjectMapper RAW = new ObjectMapper();

    private static PostgresTestDatabase db;

    @BeforeAll
    static void createDatabase() {
        db = PostgresTestDatabase.createMigrated();
        db.runScript("db/seed/product-seed.sql");
        try (Connection c = db.connect(); Statement st = c.createStatement()) {
            st.execute("UPDATE credit_balance SET paid_credit = 1000000 WHERE user_id IN (" + ALICE + ", " + BOB + ")");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> db.jdbcUrl());
        registry.add("spring.datasource.username", PostgresTestDatabase::adminUser);
        registry.add("spring.datasource.password", PostgresTestDatabase::adminPassword);
    }

    @AfterAll
    static void dropDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @LocalServerPort int port;
    @MockitoBean AccessTokenBlacklistService blacklist;
    @MockitoSpyBean RunEventSignals signals;
    @Autowired JwtTokenProvider tokens;
    @Autowired RunEventStore store;
    @Autowired RunTerminalService terminals;
    @Autowired RunStreamService streams;
    @Autowired RequestUsageService usage;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    // ------------------------------------------------------------------
    // 최초 POST · 완료 replay · 조회 일치
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST는 seq 1 run.created부터 보내고 live로 이어 terminal 뒤 닫는다. GET replay·대화 조회·상태가 같은 canonical 결과다")
    void postStreamsLiveThenReplayAndViewsAgree() throws Exception {
        String requestId = UUID.randomUUID().toString();
        try (Sse post = post(alice(), requestBody(requestId, null))) {
            assertThat(post.status).isEqualTo(200);
            assertThat(post.contentType).startsWith("text/event-stream");
            Block created = post.nextFrame();
            JsonNode createdFrame = created.json();
            UUID runId = UUID.fromString(createdFrame.get("runId").asText());
            assertThat(created.event).isEqualTo("run.created");
            assertThat(created.id).isEqualTo(runId + ":1");
            assertThat(created.raw).isEqualTo("event: run.created\nid: " + runId + ":1\ndata: " + created.data);
            assertThat(createdFrame.at("/payload/requestId").asText()).isEqualTo(requestId);

            claim(runId);
            activity(runId, "activity-1", 0);
            activity(runId, "activity-1", 1);
            settleClarification(runId);

            List<Block> live = new ArrayList<>(List.of(created));
            live.addAll(post.framesUntilEnd());
            assertThat(seqs(live)).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
            assertThat(types(live)).containsExactly("run.created", "activity.started", "activity.updated",
                    "response.page.started", "response.page.completed", "run.completed");
            assertStreamContract(live);

            // 완료 뒤 GET replay(cursor 없음)는 같은 frame을 같은 바이트로 준다
            try (Sse replay = events(alice(), runId, null, null)) {
                List<Block> replayed = replay.framesUntilEnd();
                assertThat(replayed.stream().map(b -> b.raw).toList())
                        .containsExactlyElementsOf(live.stream().map(b -> b.raw).toList());
            }

            JsonNode completed = live.get(live.size() - 1).json().get("payload");
            JsonNode page = getJson(alice(), "/api/conversations/" + createdFrame.at("/payload/noteId").asText()
                    + "/messages");
            assertSchema("ConversationPageResponse", page);
            JsonNode messages = page.at("/result/messages");
            assertThat(messages).hasSize(2);
            assertThat(messages.get(0).at("/user/text").asText()).isEqualTo("이 문제를 풀어줘");
            assertThat(messages.get(0).at("/user/requestedTasks/0").asText()).isEqualTo("solve");
            JsonNode assistant = messages.get(1);
            assertThat(assistant.get("status").asText()).isEqualTo("COMPLETED");
            assertThat(assistant.at("/assistant/assistantResponse")).isEqualTo(completed.get("assistantResponse"));
            assertThat(assistant.at("/assistant/responseDisposition")).isEqualTo(completed.get("responseDisposition"));
            assertThat(assistant.at("/assistant/clarificationReason")).isEqualTo(completed.get("clarificationReason"));
            assertThat(page.at("/result/activeRun").isNull()).isTrue();

            JsonNode status = getJson(alice(), "/api/ai-runs/" + runId);
            assertSchema("RunStatusResponse", status);
            assertThat(status.at("/result/status").asText()).isEqualTo("COMPLETED");
            assertThat(status.at("/result/lastSeq").asLong()).isEqualTo(6);
            assertThat(status.at("/result/cancelable").asBoolean()).isFalse();
            assertThat(status.at("/result/requestId").asText()).isEqualTo(requestId);
        }
        awaitNoOpenStreams();
    }

    @Test
    @DisplayName("같은 requestId 재전송은 기존 run을 seq 1부터 replay하고 run·차감을 늘리지 않는다")
    void resendReplaysSameRun() throws Exception {
        String requestId = UUID.randomUUID().toString();
        UUID runId;
        try (Sse first = post(alice(), requestBody(requestId, null))) {
            runId = UUID.fromString(first.nextFrame().json().get("runId").asText());
        }
        try (Sse again = post(alice(), requestBody(requestId, null))) {
            Block created = again.nextFrame();
            assertThat(created.id).isEqualTo(runId + ":1");
        }
        assertThat(count("SELECT count(*) FROM ai_runs WHERE request_id = ?", requestId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM request_usage_charges WHERE request_id = ?", requestId)).isEqualTo(1);
        assertThat(status(runId)).as("연결 종료는 실행을 취소하지 않는다").isEqualTo("PENDING");
    }

    // ------------------------------------------------------------------
    // 단절·재접속
    // ------------------------------------------------------------------

    @Test
    @DisplayName("중간 단절은 구독만 해제한다. 끊긴 동안 commit된 event와 terminal은 Last-Event-ID 재접속이 정확히 이어 받는다")
    void disconnectThenResumeWithLastEventId() throws Exception {
        String requestId = UUID.randomUUID().toString();
        UUID runId;
        List<Block> seen = new ArrayList<>();
        try (Sse post = post(alice(), requestBody(requestId, null))) {
            seen.add(post.nextFrame());
            runId = UUID.fromString(seen.get(0).json().get("runId").asText());
            claim(runId);
            activity(runId, "activity-1", 0);
            activity(runId, "activity-1", 1);
            seen.add(post.nextFrame());
            seen.add(post.nextFrame());
        }
        // 서버는 다음 쓰기(heartbeat)에서 단절을 알고 구독을 푼다. run은 그대로 실행 중이다.
        awaitNoOpenStreams();
        assertThat(status(runId)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT cancel_requested_at IS NULL FROM ai_runs WHERE run_id = ?",
                Boolean.class, runId)).isTrue();

        // 구독자가 없을 때 commit(방송 유실)
        activity(runId, "activity-1", 2);
        settleClarification(runId);

        String last = seen.get(seen.size() - 1).id;
        assertThat(last).isEqualTo(runId + ":3");
        List<Block> resumed;
        try (Sse resume = events(alice(), runId, last, null)) {
            resumed = resume.framesUntilEnd();
        }
        assertThat(seqs(resumed)).containsExactly(4L, 5L, 6L, 7L);
        List<Block> all = new ArrayList<>(seen);
        all.addAll(resumed);
        assertStreamContract(all);

        // query cursor도 같은 결과, 마지막 seq cursor는 0 frame 뒤 종료
        try (Sse byQuery = events(alice(), runId, null, last)) {
            assertThat(byQuery.framesUntilEnd().stream().map(b -> b.raw).toList())
                    .containsExactlyElementsOf(resumed.stream().map(b -> b.raw).toList());
        }
        try (Sse atEnd = events(alice(), runId, runId + ":7", null)) {
            assertThat(atEnd.framesUntilEnd()).isEmpty();
        }
        assertThat(count("SELECT count(*) FROM ai_runs WHERE request_id = ?", requestId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM request_usage_charges WHERE request_id = ?", requestId)).isEqualTo(1);
    }

    @Test
    @DisplayName("동시 append 중 임의 지점 단절·재접속을 반복해도 각 client가 seq 1..N을 빠짐·중복 없이 받는다")
    void concurrentAppendsAcrossReconnects() throws Exception {
        UUID runId;
        try (Sse post = post(alice(), requestBody(UUID.randomUUID().toString(), null))) {
            runId = UUID.fromString(post.nextFrame().json().get("runId").asText());
        }
        claim(runId);
        activity(runId, "activity-1", 0);
        int updates = 60;
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> producer = pool.submit(() -> {
                for (int revision = 1; revision <= updates; revision++) {
                    activity(runId, "activity-1", revision);
                    sleepQuietly(ThreadLocalRandom.current().nextInt(0, 15));
                }
                settleClarification(runId);
                return null;
            });
            Future<List<Long>> clientA = pool.submit(() -> reconnectingClient(runId));
            Future<List<Long>> clientB = pool.submit(() -> reconnectingClient(runId));
            producer.get(60, TimeUnit.SECONDS);
            long lastSeq = jdbc.queryForObject("SELECT last_event_seq FROM ai_runs WHERE run_id = ?", Long.class,
                    runId);
            List<Long> expected = new ArrayList<>();
            for (long seq = 1; seq <= lastSeq; seq++) {
                expected.add(seq);
            }
            assertThat(clientA.get(60, TimeUnit.SECONDS)).containsExactlyElementsOf(expected);
            assertThat(clientB.get(60, TimeUnit.SECONDS)).containsExactlyElementsOf(expected);
        } finally {
            pool.shutdownNow();
        }
        awaitNoOpenStreams();
    }

    private List<Long> reconnectingClient(UUID runId) throws Exception {
        List<Long> received = new ArrayList<>();
        String cursor = null;
        while (true) {
            int take = ThreadLocalRandom.current().nextInt(1, 9);
            try (Sse sse = events(alice(), runId, cursor, null)) {
                assertThat(sse.status).isEqualTo(200);
                for (int i = 0; i < take; i++) {
                    Block block = sse.nextFrameOrEnd();
                    if (block == null) {
                        return received;
                    }
                    received.add(block.seq());
                    cursor = block.id;
                    if (block.event.equals("run.completed")) {
                        return received;
                    }
                }
            } // 임의 지점 단절
        }
    }

    @Test
    @DisplayName("신호가 모두 유실되어도 주기 catch-up이 DB에서 이어 보낸다")
    void lostSignalsRecoveredFromDatabase() throws Exception {
        UUID runId;
        try (Sse post = post(alice(), requestBody(UUID.randomUUID().toString(), null))) {
            runId = UUID.fromString(post.nextFrame().json().get("runId").asText());
        }
        claim(runId);
        doReturn((RunEventSignals.Subscription) () -> { }).when(signals).subscribe(eq(runId), any());
        try (Sse sse = events(alice(), runId, runId + ":1", null)) {
            activity(runId, "activity-1", 0);
            settleClarification(runId);
            assertThat(seqs(sse.framesUntilEnd())).containsExactly(2L, 3L, 4L, 5L);
        } finally {
            Mockito.reset(signals);
        }
    }

    @Test
    @DisplayName("heartbeat는 id·data 없는 comment이고 event row·seq를 만들지 않는다")
    void heartbeatIsCommentOnly() throws Exception {
        try (Sse post = post(alice(), requestBody(UUID.randomUUID().toString(), null))) {
            UUID runId = UUID.fromString(post.nextFrame().json().get("runId").asText());
            List<Block> comments = new ArrayList<>();
            while (comments.size() < 3) {
                Block block = post.next();
                assertThat(block).isNotNull();
                assertThat(block.event).as("heartbeat 사이에 frame이 없다").isNull();
                comments.add(block);
            }
            assertThat(comments).allSatisfy(block -> {
                assertThat(block.raw).isEqualTo(": heartbeat");
                assertThat(block.id).isNull();
                assertThat(block.data).isNull();
            });
            assertThat(count("SELECT count(*) FROM ai_run_events WHERE run_id = ?", runId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT last_event_seq FROM ai_runs WHERE run_id = ?", Long.class, runId))
                    .isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------
    // commit 전 비공개
    // ------------------------------------------------------------------

    @Test
    @DisplayName("terminal transaction commit 전에는 stream·상태·대화 조회 어디에도 page·완료·본문이 보이지 않는다")
    void nothingVisibleBeforeTerminalCommit() throws Exception {
        try (Sse post = post(alice(), requestBody(UUID.randomUUID().toString(), null))) {
            Block created = post.nextFrame();
            UUID runId = UUID.fromString(created.json().get("runId").asText());
            String noteId = created.json().at("/payload/noteId").asText();
            claim(runId);

            tx.executeWithoutResult(status -> {
                TerminalSettlement result = terminals.settle(clarificationEvent(runId));
                assertThat(result.outcome()).isEqualTo(TerminalSettlement.Outcome.COMMITTED);
                try {
                    JsonNode runStatus = getJson(alice(), "/api/ai-runs/" + runId);
                    assertThat(runStatus.at("/result/status").asText()).isEqualTo("RUNNING");
                    assertThat(runStatus.at("/result/lastSeq").asLong()).isEqualTo(1);
                    JsonNode assistant = getJson(alice(), "/api/conversations/" + noteId + "/messages")
                            .at("/result/messages/1");
                    assertThat(assistant.get("status").asText()).isEqualTo("PENDING");
                    assertThat(assistant.at("/assistant/assistantResponse").isNull()).isTrue();
                    try (Sse replay = events(alice(), runId, null, null)) {
                        assertThat(replay.nextFrame().seq()).isEqualTo(1);
                        assertThat(replay.nextFrameWithin(Duration.ofMillis(1500))).isNull();
                    }
                    assertThat(post.nextFrameWithin(Duration.ofMillis(300))).as("열린 POST stream도 받지 않는다").isNull();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            assertThat(types(post.framesUntilEnd()))
                    .containsExactly("response.page.started", "response.page.completed", "run.completed");
        }
    }

    // ------------------------------------------------------------------
    // 권한·판정
    // ------------------------------------------------------------------

    @Test
    @DisplayName("타인 run·없는 run·형식이 다른 runId·타인 Note는 같은 404 RUN4041 JSON이고 상태·본문을 드러내지 않는다")
    void otherUsersRunsAreIndistinguishableFromMissing() throws Exception {
        UUID runId;
        String noteId;
        try (Sse post = post(alice(), requestBody(UUID.randomUUID().toString(), null))) {
            JsonNode created = post.nextFrame().json();
            runId = UUID.fromString(created.get("runId").asText());
            noteId = created.at("/payload/noteId").asText();
        }
        JsonNode expected = load("responses/valid/error-run4041.json").get("body");
        List<String> paths = List.of(
                "/api/ai-runs/" + runId + "/events", "/api/ai-runs/" + runId,
                "/api/ai-runs/" + UUID.randomUUID() + "/events", "/api/ai-runs/" + UUID.randomUUID(),
                "/api/ai-runs/" + runId.toString().toUpperCase() + "/events", "/api/ai-runs/not-a-run",
                "/api/conversations/" + noteId + "/messages", "/api/conversations/987654321/messages");
        for (String path : paths) {
            HttpResponse<String> response = get(bob(), path, runId + ":1");
            assertThat(response.statusCode()).as(path).isEqualTo(404);
            assertThat(response.headers().firstValue("Content-Type").orElse("")).as(path)
                    .startsWith("application/json");
            assertThat(RAW.readTree(response.body())).as(path).isEqualTo(expected);
        }
        HttpResponse<String> unauthenticated = http.send(HttpRequest.newBuilder(uri("/api/ai-runs/" + runId + "/events"))
                .header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(unauthenticated.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("cursor 판정표(cursors/cases.json)를 실제 HTTP로: 형식·범위 RUN4003, 다른 run RUN4004, replay 시작점·live 여부")
    void cursorDecisionTable() throws Exception {
        JsonNode table = load("cursors/cases.json");
        UUID terminal = terminalRun();
        UUID active = activeRun();
        Map<String, UUID> runs = Map.of("terminal", terminal, "active", active);
        Map<String, Long> lastSeqs = Map.of("terminal", lastSeq(terminal), "active", lastSeq(active));

        for (JsonNode testCase : table.get("cases")) {
            String name = testCase.get("name").asText();
            String run = testCase.get("run").asText();
            JsonNode fixtureRun = table.at("/runs/" + run);
            UUID runId = runs.get(run);
            String header = mapCursor(testCase.get("lastEventIdHeader"), fixtureRun, runId, lastSeqs.get(run));
            String query = mapCursor(testCase.get("lastEventIdQuery"), fixtureRun, runId, lastSeqs.get(run));
            JsonNode expected = testCase.get("expected");
            if (header != null && query == null && !header.equals(header.strip())) {
                // HTTP header 값은 앞뒤 공백이 OWS로 잘리고 줄바꿈을 담을 수 없다 — 같은 원문을 query로 보낸다
                query = header;
                header = null;
            }
            try (Sse sse = events(alice(), runId, header, query)) {
                assertThat(sse.status).as(name).isEqualTo(expected.get("http").asInt());
                if (sse.status != 200) {
                    assertThat(RAW.readTree(sse.errorBody).get("code").asText()).as(name)
                            .isEqualTo(expected.get("code").asText());
                    assertThat(sse.contentType).as(name).startsWith("application/json");
                    continue;
                }
                long from = mapSeq(expected.get("replayFromSeq").asLong() - 1, fixtureRun, lastSeqs.get(run)) + 1;
                List<Long> expectedSeqs = new ArrayList<>();
                for (long seq = from; seq <= lastSeqs.get(run); seq++) {
                    expectedSeqs.add(seq);
                }
                if (expected.get("thenLive").asBoolean()) {
                    List<Long> received = new ArrayList<>();
                    for (int i = 0; i < expectedSeqs.size(); i++) {
                        received.add(sse.nextFrame().seq());
                    }
                    assertThat(received).as(name).containsExactlyElementsOf(expectedSeqs);
                    assertThat(sse.nextFrameWithin(Duration.ofMillis(400))).as(name + " live").isNull();
                    assertThat(sse.ended()).as(name + " live").isFalse();
                } else {
                    assertThat(seqs(sse.framesUntilEnd())).as(name).containsExactlyElementsOf(expectedSeqs);
                }
            }
        }
        for (JsonNode access : table.get("accessCases")) {
            UUID target = access.get("access").asText().equals("missing") ? UUID.randomUUID() : bobRun();
            try (Sse sse = events(alice(), target, target + ":1", null)) {
                assertThat(sse.status).as(access.get("name").asText()).isEqualTo(404);
                assertThat(RAW.readTree(sse.errorBody).get("code").asText()).isEqualTo("RUN4041");
            }
        }
    }

    @Test
    @DisplayName("stream 전 거부는 Accept가 SSE여도 JSON이다(구조 RUN4001·깨진 JSON·Active Run RUN4091)")
    void rejectionsBeforeStreamAreJson() throws Exception {
        try (Sse invalid = post(alice(), "{}")) {
            assertThat(invalid.status).isEqualTo(400);
            assertThat(invalid.contentType).startsWith("application/json");
            assertThat(RAW.readTree(invalid.errorBody)).isEqualTo(load("responses/valid/error-run4001.json").get("body"));
        }
        try (Sse broken = post(alice(), "{\"requestId\":")) {
            assertThat(broken.status).isEqualTo(400);
            assertThat(RAW.readTree(broken.errorBody).get("code").asText()).isEqualTo("RUN4001");
        }
        try (Sse first = post(alice(), requestBody(UUID.randomUUID().toString(), null))) {
            JsonNode created = first.nextFrame().json();
            String noteId = created.at("/payload/noteId").asText();
            try (Sse conflict = post(alice(), requestBody(UUID.randomUUID().toString(), noteId))) {
                assertThat(conflict.status).isEqualTo(409);
                JsonNode body = RAW.readTree(conflict.errorBody);
                assertThat(body.get("code").asText()).isEqualTo("RUN4091");
                assertThat(body.at("/result/activeRunId").asText()).isEqualTo(created.get("runId").asText());
            }
            JsonNode page = getJson(alice(), "/api/conversations/" + noteId + "/messages");
            assertSchema("ConversationPageResponse", page);
            assertThat(page.at("/result/activeRun/runId").asText()).isEqualTo(created.get("runId").asText());
            assertThat(page.at("/result/activeRun/status").asText()).isEqualTo("PENDING");
            assertThat(page.at("/result/messages/1/assistant/assistantResponse").isNull()).isTrue();
        }
        HttpResponse<String> badLimit = get(alice(), "/api/conversations/2001/messages?limit=101", null);
        assertThat(badLimit.statusCode()).isEqualTo(400);
    }

    // ------------------------------------------------------------------
    // source availability · 대화 조회 페이지
    // ------------------------------------------------------------------

    @Test
    @DisplayName("source가 있는 완료: replay·대화 조회의 assistantResponse가 같고 asset 삭제 뒤에는 available만 false가 된다")
    void availabilityIsReadTimeOnly() throws Exception {
        JsonNode pair = load("../terminal-v2/pairs/direct-verified.json");
        SeededRun run = seededRun(pair.get("source"));
        TerminalSettlement result = terminals.settle(InternalRunWire.read(bind(pair.get("source"), run),
                SourceEvent.class));
        assertThat(result.outcome()).isEqualTo(TerminalSettlement.Outcome.COMMITTED);

        List<Block> before;
        try (Sse replay = events(alice(), run.runId(), null, null)) {
            before = replay.framesUntilEnd();
        }
        assertStreamContract(before);
        JsonNode completed = before.get(before.size() - 1).json().get("payload");
        JsonNode references = completed.at("/assistantResponse/sourceReferences");
        assertThat(references).isNotEmpty();
        references.forEach(ref -> assertThat(ref.get("available").asBoolean()).isTrue());
        JsonNode viewBefore = getJson(alice(), "/api/conversations/" + run.noteId() + "/messages");
        assertSchema("ConversationPageResponse", viewBefore);
        assertThat(viewBefore.at("/result/messages/1/assistant/assistantResponse"))
                .isEqualTo(completed.get("assistantResponse"));
        assertThat(viewBefore.at("/result/messages/1/assistant/verificationSummary"))
                .isEqualTo(completed.get("verificationSummary"));

        for (long asset : run.assets().values()) {
            jdbc.update("""
                    UPDATE assets SET status = 'DELETING', deletion_operation_id = ?, deletion_requested_at = now(),
                           file_name = 'renamed.pdf'
                    WHERE id = ?
                    """, UUID.randomUUID(), asset);
        }
        List<Block> after;
        try (Sse replay = events(alice(), run.runId(), null, null)) {
            after = replay.framesUntilEnd();
        }
        assertThat(after).hasSameSizeAs(before);
        for (int i = 0; i < before.size() - 1; i++) {
            assertThat(after.get(i).raw).isEqualTo(before.get(i).raw);
        }
        JsonNode completedAfter = after.get(after.size() - 1).json().get("payload");
        completedAfter.at("/assistantResponse/sourceReferences")
                .forEach(ref -> assertThat(ref.get("available").asBoolean()).isFalse());
        assertThat(withoutAvailability(completedAfter)).isEqualTo(withoutAvailability(completed));
        JsonNode viewAfter = getJson(alice(), "/api/conversations/" + run.noteId() + "/messages");
        assertThat(viewAfter.at("/result/messages/1/assistant/assistantResponse"))
                .isEqualTo(completedAfter.get("assistantResponse"));
    }

    @Test
    @DisplayName("대화 조회는 before·limit으로 오래된 쪽을 읽고 turn의 user·assistant를 페이지 경계로 가르지 않는다")
    void conversationPagination() throws Exception {
        String noteId = null;
        List<String> runIds = new ArrayList<>();
        for (int turn = 0; turn < 3; turn++) {
            try (Sse post = post(alice(), requestBody(UUID.randomUUID().toString(), noteId))) {
                JsonNode created = post.nextFrame().json();
                noteId = created.at("/payload/noteId").asText();
                UUID runId = UUID.fromString(created.get("runId").asText());
                runIds.add(runId.toString());
                claim(runId);
                settleClarification(runId);
            }
        }
        // 6개 메시지, limit 3 → 최신 3개 중 맨 앞 assistant는 다음 페이지로
        JsonNode first = getJson(alice(), "/api/conversations/" + noteId + "/messages?limit=3").get("result");
        assertThat(roles(first)).containsExactly("user", "assistant");
        assertThat(first.at("/messages/0/runId").asText()).isEqualTo(runIds.get(2));
        String nextBefore = first.get("nextBefore").asText();
        assertThat(nextBefore).isEqualTo(first.at("/messages/0/messageId").asText());

        JsonNode second = getJson(alice(), "/api/conversations/" + noteId + "/messages?limit=3&before=" + nextBefore)
                .get("result");
        assertThat(roles(second)).containsExactly("user", "assistant");
        assertThat(second.at("/messages/0/runId").asText()).isEqualTo(runIds.get(1));
        JsonNode third = getJson(alice(), "/api/conversations/" + noteId + "/messages?limit=3&before="
                + second.get("nextBefore").asText()).get("result");
        assertThat(roles(third)).containsExactly("user", "assistant");
        assertThat(third.get("nextBefore").isNull()).isTrue();

        JsonNode all = getJson(alice(), "/api/conversations/" + noteId + "/messages");
        assertSchema("ConversationPageResponse", all);
        assertThat(all.at("/result/messages")).hasSize(6);
    }

    // ------------------------------------------------------------------
    // fake producer (coordinator 대역 — production 저장 경로)
    // ------------------------------------------------------------------

    private void claim(UUID runId) {
        jdbc.update("""
                UPDATE ai_runs SET status = 'RUNNING', attempt = 1, fence_token = ?, started_at = now(),
                       lease_owner = 'coordinator-1', lease_expires_at = now() + interval '30 seconds', heartbeat_at = now()
                WHERE run_id = ?
                """, FENCE, runId);
    }

    private void activity(UUID runId, String activityId, int revision) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("kind", "input_analysis")
                .put("activity_id", activityId);
        String type;
        if (revision == 0) {
            type = "activity.started";
            payload.put("label", "analyzing_input");
        } else {
            type = "activity.updated";
            payload.put("status", "in_progress").put("message", "입력을 읽고 있다");
        }
        ObjectNode envelope = JsonNodeFactory.instance.objectNode();
        envelope.put("contract_version", "brain-source-v1");
        envelope.put("run_id", runId.toString());
        envelope.put("attempt", 1);
        envelope.put("fence_token", FENCE);
        envelope.put("source_event_id", runId + ":" + activityId + ":r" + revision + ":" + type.substring(9));
        envelope.put("occurred_at", Instant.now().truncatedTo(ChronoUnit.MICROS).toString());
        envelope.put("type", type);
        envelope.set("payload", payload);
        ActivityAppendResult result = store.appendActivity(InternalRunWire.read(envelope.toString(), SourceEvent.class));
        assertThat(result.isStored()).as(result.outcome().toString()).isTrue();
    }

    private SourceEvent clarificationEvent(UUID runId) {
        ObjectNode source = (ObjectNode) load("../terminal-v2/pairs/clarification.json").get("source").deepCopy();
        source.put("run_id", runId.toString()).put("attempt", 1).put("fence_token", FENCE)
                .put("source_event_id", runId + ":terminal");
        return InternalRunWire.read(source, SourceEvent.class);
    }

    private void settleClarification(UUID runId) {
        TerminalSettlement result = terminals.settle(clarificationEvent(runId));
        assertThat(result.outcome()).isEqualTo(TerminalSettlement.Outcome.COMMITTED);
    }

    private UUID terminalRun() throws Exception {
        UUID runId;
        try (Sse post = post(alice(), requestBody(UUID.randomUUID().toString(), null))) {
            runId = UUID.fromString(post.nextFrame().json().get("runId").asText());
        }
        claim(runId);
        activity(runId, "activity-1", 0);
        for (int revision = 1; revision <= 9; revision++) {
            activity(runId, "activity-1", revision);
        }
        settleClarification(runId);
        return runId;
    }

    private UUID activeRun() throws Exception {
        UUID runId;
        try (Sse post = post(alice(), requestBody(UUID.randomUUID().toString(), null))) {
            runId = UUID.fromString(post.nextFrame().json().get("runId").asText());
        }
        claim(runId);
        activity(runId, "activity-1", 0);
        activity(runId, "activity-1", 1);
        activity(runId, "activity-1", 2);
        return runId;
    }

    private UUID bobRun() throws Exception {
        try (Sse post = post(bob(), requestBody(UUID.randomUUID().toString(), null))) {
            return UUID.fromString(post.nextFrame().json().get("runId").asText());
        }
    }

    /** fixture cursor를 이 테스트 run에 맞춘다: runId 치환, fixture 마지막 seq(+1)는 실제 마지막 seq(+1)로. */
    private static String mapCursor(JsonNode value, JsonNode fixtureRun, UUID runId, long lastSeq) {
        if (value == null || value.isNull()) {
            return null;
        }
        String fixtureId = fixtureRun.get("runId").asText();
        String cursor = value.asText().replace(fixtureId, runId.toString())
                .replace(fixtureId.toUpperCase(), runId.toString().toUpperCase());
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(":([0-9]+)\\z").matcher(cursor);
        if (cursor.startsWith(runId.toString()) && matcher.find()) {
            long seq = Long.parseLong(matcher.group(1));
            if (!matcher.group(1).startsWith("0")) {
                cursor = cursor.substring(0, matcher.start()) + ":" + mapSeq(seq, fixtureRun, lastSeq);
            }
        }
        return cursor;
    }

    private static long mapSeq(long seq, JsonNode fixtureRun, long lastSeq) {
        long fixtureLast = fixtureRun.get("lastDurableSeq").asLong();
        if (seq == fixtureLast) {
            return lastSeq;
        }
        if (seq == fixtureLast + 1) {
            return lastSeq + 1;
        }
        return seq;
    }

    record SeededRun(UUID runId, long noteId, Map<String, Long> assets) {
    }

    /** source 있는 terminal용: 접수(1.79)가 남기는 모양을 SQL로 만든다(asset·dependency·policy version 포함). */
    private SeededRun seededRun(JsonNode source) {
        return tx.execute(status -> {
            long noteId = jdbc.queryForObject("""
                    INSERT INTO notes (user_id, title, created_at, updated_at) VALUES (?, 'sources', now(), now())
                    RETURNING note_id
                    """, Long.class, ALICE);
            Map<String, Long> assets = new LinkedHashMap<>();
            source.at("/payload/selected_page_provenance").forEach(item -> assets.computeIfAbsent(
                    item.get("asset_id").asText(), brainId -> jdbc.queryForObject("""
                            INSERT INTO assets (user_id, note_id, file_name, file_size, mime_type, object_key, source,
                                                status, content_version, sha256, page_count, created_at, updated_at)
                            VALUES (?, ?, 'concept.pdf', 1024, 'application/pdf', 'k', 'upload', 'ACTIVE', 1, ?, 20,
                                    now(), now())
                            RETURNING id
                            """, Long.class, ALICE, noteId, ASSET_SHA256)));
            UUID userMessage = UUID.randomUUID();
            UUID assistantMessage = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO chat_messages (message_id, note_id, role, status, user_input, plain_text, created_at)
                    VALUES (?, ?, 'USER', 'COMPLETED', '{"text":"풀어줘"}', '풀어줘', now()),
                           (?, ?, 'ASSISTANT', 'PENDING', NULL, NULL, now() + interval '1 microsecond')
                    """, userMessage, noteId, assistantMessage, noteId);
            ObjectNode request = JsonNodeFactory.instance.objectNode();
            request.putObject("input").put("text", "풀어줘");
            request.putArray("verified_solution_refs");
            ObjectNode policy = JsonNodeFactory.instance.objectNode();
            policy.putObject("asset_versions").put("page_understanding_version", "page-understanding-v1");
            UUID runId = UUID.randomUUID();
            String requestId = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO ai_runs (run_id, request_id, user_id, note_id, user_message_id, assistant_message_id,
                                         request_fingerprint, request_snapshot, policy_snapshot, budget_policy_version,
                                         request_deadline_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), 'budget-v1', ?, now())
                    """, runId, requestId, ALICE, noteId, userMessage, assistantMessage, "f".repeat(64),
                    request.toString(), policy.toString(),
                    OffsetDateTime.ofInstant(Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MICROS),
                            ZoneOffset.UTC));
            for (long asset : assets.values()) {
                jdbc.update("""
                        INSERT INTO run_asset_dependencies (run_id, note_id, asset_id, dependency_kind, content_version)
                        VALUES (?, ?, ?, 'CORPUS_SNAPSHOT', 1)
                        """, runId, noteId, asset);
            }
            usage.chargeRequest(ALICE, requestId, runId);
            ObjectNode created = JsonNodeFactory.instance.objectNode().put("requestId", requestId)
                    .put("noteId", String.valueOf(noteId)).put("userMessageId", userMessage.toString())
                    .put("assistantMessageId", assistantMessage.toString()).put("status", "PENDING");
            created.putArray("requestedTasks").add("solve");
            created.putArray("requestedOutputs").add("text");
            store.appendServerEvent(runId, com.proovy.domain.run.contract.ServerFrameType.RUN_CREATED, created,
                    Instant.now());
            claim(runId);
            return new SeededRun(runId, noteId, assets);
        });
    }

    /** fixture source를 seeded run에 묶는다(RunTerminalServicePostgresTest와 같은 규칙). */
    private static ObjectNode bind(JsonNode source, SeededRun run) {
        ObjectNode bound = (ObjectNode) source.deepCopy();
        bound.put("run_id", run.runId().toString()).put("attempt", 1).put("fence_token", FENCE);
        bound.at("/payload/assistant_response/source_references").forEach(ref ->
                ((ObjectNode) ref).put("asset_id", String.valueOf(run.assets().get(ref.get("asset_id").asText()))));
        bound.at("/payload/selected_page_provenance").forEach(item -> ((ObjectNode) item)
                .put("asset_id", String.valueOf(run.assets().get(item.get("asset_id").asText())))
                .put("content_version", ASSET_SHA256));
        return bound;
    }

    // ------------------------------------------------------------------
    // 검사 도우미
    // ------------------------------------------------------------------

    private void assertStreamContract(List<Block> blocks) throws Exception {
        ArrayNode frames = JsonNodeFactory.instance.arrayNode();
        for (Block block : blocks) {
            JsonNode frame = block.json();
            assertThat(schemaErrors("BrowserFrame", frame)).as(block.id).isEmpty();
            assertThat(block.id).isEqualTo(frame.get("runId").asText() + ":" + frame.get("seq").asLong());
            assertThat(block.event).isEqualTo(frame.get("type").asText());
            frames.add(frame);
        }
        assertThat(BrowserRunStreamContractTest.violations(frames)).isEmpty();
    }

    private static void assertSchema(String definition, JsonNode body) {
        assertThat(schemaErrors(definition, body)).as(definition + " " + body).isEmpty();
    }

    private static JsonNode withoutAvailability(JsonNode payload) {
        ObjectNode copy = (ObjectNode) payload.deepCopy();
        copy.at("/assistantResponse/sourceReferences").forEach(ref -> ((ObjectNode) ref).remove("available"));
        return copy;
    }

    private static List<String> roles(JsonNode page) {
        List<String> roles = new ArrayList<>();
        page.get("messages").forEach(message -> roles.add(message.get("role").asText()));
        return roles;
    }

    private static List<Long> seqs(List<Block> blocks) {
        return blocks.stream().map(Block::seq).toList();
    }

    private static List<String> types(List<Block> blocks) {
        return blocks.stream().map(b -> b.event).toList();
    }

    private String status(UUID runId) {
        return jdbc.queryForObject("SELECT status FROM ai_runs WHERE run_id = ?", String.class, runId);
    }

    private long lastSeq(UUID runId) {
        return jdbc.queryForObject("SELECT last_event_seq FROM ai_runs WHERE run_id = ?", Long.class, runId);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private void awaitNoOpenStreams() throws InterruptedException {
        await(() -> streams.openStreams() == 0);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met in time");
            }
            Thread.sleep(20);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // HTTP · SSE client
    // ------------------------------------------------------------------

    private String alice() {
        return tokens.generateTokens(ALICE).accessToken();
    }

    private String bob() {
        return tokens.generateTokens(BOB).accessToken();
    }

    private static String requestBody(String requestId, String noteId) {
        ObjectNode body = JsonNodeFactory.instance.objectNode().put("requestId", requestId).put("text", "이 문제를 풀어줘");
        if (noteId != null) {
            body.put("noteId", noteId);
        }
        body.putArray("requestedTasks").add("solve");
        body.putArray("requestedOutputs").add("text");
        return body.toString();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private Sse post(String token, String body) throws Exception {
        return Sse.open(http, HttpRequest.newBuilder(uri("/api/conversations"))
                .header("Authorization", "Bearer " + token).header("Accept", "text/event-stream")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private Sse events(String token, UUID runId, String header, String query) throws Exception {
        String path = "/api/ai-runs/" + runId + "/events"
                + (query == null ? "" : "?lastEventId=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token)
                .header("Accept", "text/event-stream").GET();
        if (header != null) {
            request.header("Last-Event-ID", header);
        }
        return Sse.open(http, request.build());
    }

    private HttpResponse<String> get(String token, String path, String lastEventId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token)
                .header("Accept", "text/event-stream").timeout(Duration.ofSeconds(10)).GET();
        if (lastEventId != null) {
            request.header("Last-Event-ID", lastEventId);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode getJson(String token, String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path + " " + response.body()).isEqualTo(200);
        return RAW.readTree(response.body());
    }

    /** SSE 블록 하나. comment 블록은 event·id·data가 null이다. raw는 끝 빈 줄을 뺀 원문이다. */
    record Block(String event, String id, String data, String raw) {

        JsonNode json() throws IOException {
            return RAW.readTree(data);
        }

        long seq() {
            return Long.parseLong(id.substring(id.indexOf(':') + 1));
        }
    }

    /** 실제 HTTP 연결로 SSE를 읽는 client. close는 연결을 끊는다(브라우저 단절). */
    static final class Sse implements AutoCloseable {

        private static final Block END = new Block(null, null, null, null);

        final int status;
        final String contentType;
        final String errorBody;
        private final InputStream body;
        private final BlockingQueue<Block> blocks = new LinkedBlockingQueue<>();
        private volatile boolean ended;

        private Sse(int status, String contentType, String errorBody, InputStream body) {
            this.status = status;
            this.contentType = contentType;
            this.errorBody = errorBody;
            this.body = body;
        }

        static Sse open(HttpClient client, HttpRequest request) throws Exception {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (response.statusCode() != 200) {
                String error;
                try (InputStream in = response.body()) {
                    error = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                Sse failed = new Sse(response.statusCode(), contentType, error, null);
                failed.ended = true;
                return failed;
            }
            Sse sse = new Sse(200, contentType, null, response.body());
            Thread reader = new Thread(sse::read, "sse-test-client");
            reader.setDaemon(true);
            reader.start();
            return sse;
        }

        private void read() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                List<String> lines = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isEmpty()) {
                        lines.add(line);
                        continue;
                    }
                    if (!lines.isEmpty()) {
                        blocks.add(parse(lines));
                        lines = new ArrayList<>();
                    }
                }
            } catch (IOException closed) {
                // close()로 끊음
            } finally {
                ended = true;
                blocks.add(END);
            }
        }

        private static Block parse(List<String> lines) {
            String event = null;
            String id = null;
            String data = null;
            for (String line : lines) {
                if (line.startsWith("event: ")) {
                    event = line.substring(7);
                } else if (line.startsWith("id: ")) {
                    id = line.substring(4);
                } else if (line.startsWith("data: ")) {
                    data = line.substring(6);
                } else if (!line.startsWith(":")) {
                    throw new AssertionError("unexpected SSE line: " + line);
                }
            }
            return new Block(event, id, data, String.join("\n", lines));
        }

        /** 다음 블록(heartbeat 포함). 연결이 끝났으면 null. */
        Block next() throws InterruptedException {
            Block block = blocks.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            if (block == null) {
                throw new AssertionError("no SSE block in time");
            }
            return block == END ? null : block;
        }

        /** heartbeat를 건너뛴 다음 frame. 반드시 있어야 한다. */
        Block nextFrame() throws InterruptedException {
            Block frame = nextFrameOrEnd();
            if (frame == null) {
                throw new AssertionError("stream ended before the next frame");
            }
            return frame;
        }

        Block nextFrameOrEnd() throws InterruptedException {
            while (true) {
                Block block = next();
                if (block == null || block.event() != null) {
                    return block;
                }
            }
        }

        /** {@code within} 안에 frame이 오면 그 frame, 아니면 null(heartbeat는 건너뜀). */
        Block nextFrameWithin(Duration within) throws InterruptedException {
            long deadline = System.nanoTime() + within.toNanos();
            while (true) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return null;
                }
                Block block = blocks.poll(left, TimeUnit.NANOSECONDS);
                if (block == null) {
                    return null;
                }
                if (block == END) {
                    blocks.add(END);
                    return null;
                }
                if (block.event() != null) {
                    return block;
                }
            }
        }

        /** 서버가 연결을 닫을 때까지의 frame. */
        List<Block> framesUntilEnd() throws InterruptedException {
            List<Block> frames = new ArrayList<>();
            Block block;
            while ((block = nextFrameOrEnd()) != null) {
                frames.add(block);
            }
            return frames;
        }

        boolean ended() {
            return ended;
        }

        @Override
        public void close() throws IOException {
            if (body != null) {
                body.close();
            }
        }
    }
}
