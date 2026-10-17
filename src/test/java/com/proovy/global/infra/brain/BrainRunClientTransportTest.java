package com.proovy.global.infra.brain;

import static com.proovy.global.infra.brain.FakeBrainServer.openSse;
import static com.proovy.global.infra.brain.FakeBrainServer.respond;
import static com.proovy.global.infra.brain.FakeBrainServer.write;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proovy.domain.run.brain.InternalRunFixtures;
import com.proovy.domain.run.brain.InternalRunRequest;
import com.proovy.domain.run.brain.InternalRunWire;
import com.proovy.domain.run.brain.RunCancelRequest;
import com.proovy.domain.run.brain.RunOperation;
import com.proovy.domain.run.brain.RunRecordState;
import com.proovy.domain.run.brain.RunStatusQuery;
import com.proovy.domain.run.brain.RuntimeCleanupRequest;
import com.proovy.domain.run.brain.SourceEvent;
import com.proovy.domain.run.brain.SourceEventType;
import com.proovy.domain.run.brain.StoredTerminalType;
import com.proovy.domain.run.entity.RunCancelCause;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import reactor.core.Disposable;

/** 가짜 Brain HTTP 서버로 인증 header·본문·오류 분류·timeout·취소를 검사한다. */
class BrainRunClientTransportTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOKEN = "test-shared-token";
    /** fixture deadline(2026-10-02T09:30Z) 30분 전으로 고정한 시계. */
    private static final Clock BEFORE_DEADLINE = Clock.fixed(Instant.parse("2026-10-02T09:00:00Z"), ZoneOffset.UTC);
    private static final Duration WAIT = Duration.ofSeconds(10);

    private FakeBrainServer brain;
    private final InternalRunRequest request = InternalRunFixtures.fullRequest();

    @BeforeEach
    void start() throws IOException {
        brain = new FakeBrainServer();
    }

    @AfterEach
    void stop() {
        brain.close();
    }

    @Test
    @DisplayName("stream: 서비스 token만 보내고 snake_case 본문으로 activity·terminal을 받은 뒤 끝난다")
    void streamHappyPath() throws Exception {
        brain.handle(exchange -> {
            OutputStream out = openSse(exchange);
            write(out, ": keep-alive\n\n");
            write(out, frame(activity("activity.started", "r0:started")));
            write(out, frame(storedTerminal("status-terminal")));
            write(out, frame(activity("activity.updated", "r1:updated")));
        });

        List<SourceEvent> events = client(BEFORE_DEADLINE).stream(request).collectList().block(WAIT);

        assertThat(events).extracting(SourceEvent::type)
                .containsExactly(SourceEventType.ACTIVITY_STARTED, SourceEventType.RUN_COMPLETED);
        FakeBrainServer.Received sent = brain.received().getFirst();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.path()).isEqualTo("/internal/v1/runs/run-441/stream");
        assertThat(sent.headers().getFirst("Authorization")).isEqualTo("Bearer " + TOKEN);
        assertThat(sent.headers().getFirst("Accept")).isEqualTo("text/event-stream");
        assertOnlyServiceHeaders(sent);
        assertThat(JSON.readTree(sent.body())).isEqualTo(JSON.readTree(InternalRunWire.write(request)));
        assertThat(sent.body()).doesNotContain("user_jwt", "authToken", "auth_token");
        assertThat(brain.received()).hasSize(1);
    }

    @Test
    @DisplayName("stream: 503 capacity·503·5xx·409 binding·409 state·400·401·413을 서로 다른 원인으로 넘기고 재시도하지 않는다")
    void streamRejections() {
        assertStreamFailure(503, "{\"error\":\"unavailable\",\"reason\":\"capacity\"}", BrainFailureKind.CAPACITY);
        assertStreamFailure(503, "{\"error\":\"unavailable\"}", BrainFailureKind.UNAVAILABLE);
        assertStreamFailure(502, "not json", BrainFailureKind.UNAVAILABLE);
        assertStreamFailure(400, "{\"error\":\"invalid_request\",\"details\":[]}", BrainFailureKind.INVALID_REQUEST);
        assertStreamFailure(401, "{\"error\":\"unauthenticated\"}", BrainFailureKind.AUTH_REJECTED);
        assertStreamFailure(403, "{\"error\":\"forbidden_identity\"}", BrainFailureKind.AUTH_REJECTED);
        assertStreamFailure(413, "{\"error\":\"request_too_large\"}", BrainFailureKind.REQUEST_TOO_LARGE);
        assertStreamFailure(404, "{}", BrainFailureKind.UNEXPECTED_STATUS);

        BrainCallException conflict = assertStreamFailure(409, "{\"error\":\"conflict\",\"state\":\"canceling\"}",
                BrainFailureKind.RUN_CONFLICT);
        assertThat(conflict.conflictState()).isEqualTo(RunRecordState.CANCELING);

        JsonNode bound = InternalRunFixtures.load("operations/stream-request-id-bound.json");
        BrainCallException binding = assertStreamFailure(bound.get("http_status").asInt(),
                bound.get("response").toString(), BrainFailureKind.REQUEST_ID_BOUND_TO_OTHER_RUN);
        assertThat(binding.existingRunId()).isEqualTo(bound.get("bound_run_id").asText());
        assertThat(binding.kind().isRecoverableByStatusQuery()).isFalse();
    }

    @Test
    @DisplayName("stream: run.canceled·다른 run_id·event 이름 불일치·깨진 JSON frame은 malformed source다")
    void malformedSource() throws Exception {
        String canceled = storedTerminal("status-terminal-canceled");
        String otherRun = activity("activity.started", "r0:started").replace("\"run-441\"", "\"run-442\"");
        String renamed = "event: activity.updated\ndata: " + activity("activity.started", "r0:started") + "\n\n";
        for (String frame : List.of(frame(canceled), frame(otherRun), renamed, "event: activity.started\ndata: {\n\n",
                "data: " + activity("activity.started", "r0:started") + "\n\n")) {
            brain.handle(exchange -> write(openSse(exchange), frame));
            assertThatThrownBy(() -> client(BEFORE_DEADLINE).stream(request).blockLast(WAIT))
                    .isInstanceOfSatisfying(BrainCallException.class,
                            error -> assertThat(error.kind()).isEqualTo(BrainFailureKind.MALFORMED_SOURCE));
        }
    }

    @Test
    @DisplayName("stream: terminal 없이 끝나면 STREAM_ENDED_WITHOUT_TERMINAL(취소된 run은 status로 확인)")
    void endedWithoutTerminal() {
        brain.handle(exchange -> write(openSse(exchange), frame(activity("activity.started", "r0:started"))));
        assertThatThrownBy(() -> client(BEFORE_DEADLINE).stream(request).blockLast(WAIT))
                .isInstanceOfSatisfying(BrainCallException.class, error -> {
                    assertThat(error.kind()).isEqualTo(BrainFailureKind.STREAM_ENDED_WITHOUT_TERMINAL);
                    assertThat(error.kind().isRecoverableByStatusQuery()).isTrue();
                });
    }

    @Test
    @DisplayName("stream: frame·keep-alive 공백이 idle 상한을 넘으면 TIMEOUT")
    void idleTimeout() {
        brain.handle(exchange -> {
            OutputStream out = openSse(exchange);
            write(out, frame(activity("activity.started", "r0:started")));
            Thread.sleep(3_000);
        });
        assertThatThrownBy(() -> client(BEFORE_DEADLINE, Duration.ofMillis(300), Duration.ofMinutes(3))
                .stream(request).blockLast(WAIT))
                .isInstanceOfSatisfying(BrainCallException.class,
                        error -> assertThat(error.kind()).isEqualTo(BrainFailureKind.TIMEOUT));
    }

    @Test
    @DisplayName("stream: keep-alive가 와도 고정 deadline + grace를 넘으면 DEADLINE_EXCEEDED")
    void deadlineBound() {
        brain.handle(exchange -> {
            OutputStream out = openSse(exchange);
            for (int i = 0; i < 50; i++) {
                write(out, ": keep-alive\n\n");
                Thread.sleep(100);
            }
        });
        Instant deadline = request.executionOptions().requestDeadlineAt().toInstant();
        Clock nearDeadline = Clock.offset(Clock.systemUTC(),
                Duration.between(Instant.now(), deadline.minusMillis(200)));
        long started = System.nanoTime();
        assertThatThrownBy(() -> client(nearDeadline, Duration.ofSeconds(2), Duration.ofMillis(500))
                .stream(request).blockLast(WAIT))
                .isInstanceOfSatisfying(BrainCallException.class,
                        error -> assertThat(error.kind()).isEqualTo(BrainFailureKind.DEADLINE_EXCEEDED));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("stream: deadline + grace가 이미 지난 요청은 보내지 않는다")
    void expiredRequestIsNotSent() {
        Clock late = Clock.fixed(Instant.parse("2026-10-17T00:00:00Z"), ZoneOffset.UTC);
        assertThatThrownBy(() -> client(late).stream(request).blockLast(WAIT))
                .isInstanceOfSatisfying(BrainCallException.class,
                        error -> assertThat(error.kind()).isEqualTo(BrainFailureKind.DEADLINE_EXCEEDED));
        assertThat(brain.received()).isEmpty();
    }

    @Test
    @DisplayName("stream: 구독 취소는 연결을 닫는다(Brain 실행 취소는 cancel API)")
    void disposeClosesConnection() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        CountDownLatch firstSent = new CountDownLatch(1);
        brain.handle(exchange -> {
            OutputStream out = openSse(exchange);
            write(out, frame(activity("activity.started", "r0:started")));
            firstSent.countDown();
            try {
                for (int i = 0; i < 200; i++) {
                    Thread.sleep(50);
                    write(out, ": keep-alive\n\n");
                }
            } catch (IOException disconnected) {
                closed.countDown();
            }
        });
        CountDownLatch received = new CountDownLatch(1);
        Disposable subscription = client(BEFORE_DEADLINE).stream(request).subscribe(event -> received.countDown(),
                error -> { });
        assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
        subscription.dispose();
        assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(brain.received()).hasSize(1);
    }

    @Test
    @DisplayName("stream: 응답 중간 단절은 TRANSPORT_LOST, 닫힌 port는 CONNECT_FAILED")
    void transportFailures() throws Exception {
        try (ServerSocket raw = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                try (Socket socket = raw.accept()) {
                    socket.getInputStream().read(new byte[64 * 1024]);
                    OutputStream out = socket.getOutputStream();
                    write(out, "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: 100000\r\n\r\n"
                            + frame(activity("activity.started", "r0:started")) + "event: activity.upd");
                    socket.setSoLinger(true, 0);
                } catch (IOException ignored) {
                    // 테스트가 판정한다
                }
            });
            BrainRunClient dropped = new BrainRunClient(properties(URI.create("http://127.0.0.1:" + raw.getLocalPort()),
                    Duration.ofSeconds(30), Duration.ofMinutes(3)), new SharedTokenCredentials(TOKEN), BEFORE_DEADLINE);
            assertThatThrownBy(() -> dropped.stream(request).blockLast(WAIT))
                    .isInstanceOfSatisfying(BrainCallException.class, error -> {
                        assertThat(error.kind()).isEqualTo(BrainFailureKind.TRANSPORT_LOST);
                        assertThat(error.kind().isRecoverableByStatusQuery()).isTrue();
                    });
        }

        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        BrainRunClient unreachable = new BrainRunClient(properties(URI.create("http://127.0.0.1:" + closedPort),
                Duration.ofSeconds(30), Duration.ofMinutes(3)), new SharedTokenCredentials(TOKEN), BEFORE_DEADLINE);
        assertThatThrownBy(() -> unreachable.stream(request).blockLast(WAIT))
                .isInstanceOfSatisfying(BrainCallException.class,
                        error -> assertThat(error.kind()).isEqualTo(BrainFailureKind.CONNECT_FAILED));
    }

    @Test
    @DisplayName("status: 본문 없는 GET에 attempt·fence_token만 싣고 저장 run.canceled terminal을 받는다")
    void statusIsObservationOnly() throws Exception {
        JsonNode fixture = InternalRunFixtures.load("operations/status-terminal-canceled.json");
        brain.handle(exchange -> respond(exchange, 200, fixture.get("response").toString()));
        RunStatusQuery query = InternalRunWire.read(fixture.get("request"), RunStatusQuery.class);

        var status = client(BEFORE_DEADLINE).status(query).block(WAIT);

        assertThat(status.terminal().type()).isEqualTo(StoredTerminalType.RUN_CANCELED);
        FakeBrainServer.Received sent = brain.received().getFirst();
        assertThat(sent.method()).isEqualTo("GET");
        assertThat(sent.path()).isEqualTo("/internal/v1/runs/run-441");
        assertThat(sent.query()).isEqualTo("attempt=2&fence_token=fence-c3d4");
        assertThat(sent.body()).isEmpty();
        assertOnlyServiceHeaders(sent);
    }

    @Test
    @DisplayName("status: 다른 run의 응답·계약 밖 본문·빈 본문은 MALFORMED_RESPONSE, 늦은 응답은 TIMEOUT")
    void statusFailures() {
        RunStatusQuery query = RunStatusQuery.of("run-441", 1, "fence-a1b2");
        String other = InternalRunFixtures.load("operations/status-absent.json").get("response").toString()
                .replace("run-441", "run-442");
        for (String body : List.of(other, "{\"state\":\"absent\"}", "")) {
            brain.handle(exchange -> respond(exchange, 200, body));
            assertThatThrownBy(() -> client(BEFORE_DEADLINE).status(query).block(WAIT))
                    .isInstanceOfSatisfying(BrainCallException.class,
                            error -> assertThat(error.kind()).isEqualTo(BrainFailureKind.MALFORMED_RESPONSE));
        }
        brain.handle(exchange -> Thread.sleep(2_000));
        BrainRunClient slow = new BrainRunClient(new BrainClientProperties(brain.baseUrl(), sharedAuth(),
                Duration.ofSeconds(5), Duration.ofMillis(300), Duration.ofSeconds(30), Duration.ofMinutes(3),
                DataSize.ofMegabytes(4)), new SharedTokenCredentials(TOKEN), BEFORE_DEADLINE);
        assertThatThrownBy(() -> slow.status(query).block(WAIT))
                .isInstanceOfSatisfying(BrainCallException.class,
                        error -> assertThat(error.kind()).isEqualTo(BrainFailureKind.TIMEOUT));
    }

    @Test
    @DisplayName("cancel·cleanup: fixture 요청 본문을 그대로 보내고 응답을 typed로 받는다")
    void cancelAndCleanup() throws Exception {
        JsonNode cancel = InternalRunFixtures.load("operations/cancel-accepted.json");
        brain.handle(exchange -> respond(exchange, 200, cancel.get("response").toString()));
        var ack = client(BEFORE_DEADLINE).cancel(RunCancelRequest.of("run-441", 1, "fence-a1b2",
                RunCancelCause.USER_REQUESTED)).block(WAIT);
        assertThat(ack.state()).isEqualTo(RunRecordState.CANCELING);
        assertThat(JSON.readTree(brain.received().getLast().body())).isEqualTo(cancel.get("request"));
        assertThat(brain.received().getLast().path()).isEqualTo("/internal/v1/runs/run-441/cancel");

        JsonNode cleanup = InternalRunFixtures.load("operations/cleanup-protected.json");
        brain.handle(exchange -> respond(exchange, 200, cleanup.get("response").toString()));
        var result = client(BEFORE_DEADLINE).cleanup(RuntimeCleanupRequest.of("run-441")).block(WAIT);
        assertThat(result.state()).isEqualTo(RunRecordState.EXECUTING);
        assertThat(JSON.readTree(brain.received().getLast().body())).isEqualTo(cleanup.get("request"));
        assertThat(brain.received().getLast().path()).isEqualTo("/internal/v1/runs/run-441/cleanup");
    }

    @Test
    @DisplayName("credential을 만들지 못하면 요청을 보내지 않고 CREDENTIALS_UNAVAILABLE")
    void credentialsFailure() {
        BrainServiceCredentials broken = () -> {
            throw new IllegalStateException("metadata server says secret-xyz");
        };
        BrainRunClient client = new BrainRunClient(properties(brain.baseUrl(), Duration.ofSeconds(30),
                Duration.ofMinutes(3)), broken, BEFORE_DEADLINE);
        assertThatThrownBy(() -> client.status(RunStatusQuery.of("run-441", 1, "fence-a1b2")).block(WAIT))
                .isInstanceOfSatisfying(BrainCallException.class, error -> {
                    assertThat(error.kind()).isEqualTo(BrainFailureKind.CREDENTIALS_UNAVAILABLE);
                    assertThat(error).hasNoCause();
                    assertThat(error.getMessage()).doesNotContain("secret-xyz");
                });
        assertThat(brain.received()).isEmpty();
    }

    @Test
    @DisplayName("오류에는 token·grant·read URL·응답 본문 원문이 없다")
    void errorsCarryNoSecrets() {
        brain.handle(exchange -> respond(exchange, 400,
                "{\"error\":\"invalid_request\",\"echo\":\"grant-opaque-token sig=abc\"}"));
        assertThatThrownBy(() -> client(BEFORE_DEADLINE).stream(request).blockLast(WAIT))
                .isInstanceOfSatisfying(BrainCallException.class, error -> {
                    assertThat(error).hasNoCause();
                    assertThat(error.getMessage()).doesNotContain(TOKEN, "grant-opaque-token", "sig=", "https://");
                    assertThat(error.getStackTrace()).isEmpty();
                });
    }

    private BrainCallException assertStreamFailure(int status, String body, BrainFailureKind kind) {
        int before = brain.received().size();
        brain.handle(exchange -> respond(exchange, status, body));
        BrainCallException error = (BrainCallException) org.assertj.core.api.Assertions.catchThrowable(
                () -> client(BEFORE_DEADLINE).stream(request).blockLast(WAIT));
        assertThat(error).as("HTTP " + status).isNotNull();
        assertThat(error.kind()).as("HTTP " + status).isEqualTo(kind);
        assertThat(error.httpStatus()).isEqualTo(status);
        assertThat(error.operation()).isEqualTo(RunOperation.STREAM);
        assertThat(brain.received()).as("no retry").hasSize(before + 1);
        return error;
    }

    private static void assertOnlyServiceHeaders(FakeBrainServer.Received sent) {
        Set<String> names = sent.headers().keySet().stream().map(String::toLowerCase).collect(Collectors.toSet());
        assertThat(names).isSubsetOf("authorization", "accept", "content-type", "content-length", "host",
                "user-agent", "accept-encoding", "connection", "transfer-encoding");
        assertThat(sent.headers().get("Authorization")).hasSize(1);
    }

    private BrainRunClient client(Clock clock) {
        return client(clock, Duration.ofSeconds(30), Duration.ofMinutes(3));
    }

    private BrainRunClient client(Clock clock, Duration idle, Duration grace) {
        return new BrainRunClient(properties(brain.baseUrl(), idle, grace), new SharedTokenCredentials(TOKEN), clock);
    }

    private static BrainClientProperties properties(URI baseUrl, Duration idle, Duration grace) {
        return new BrainClientProperties(baseUrl, sharedAuth(), Duration.ofSeconds(2), Duration.ofSeconds(5), idle,
                grace, DataSize.ofMegabytes(4));
    }

    private static BrainClientProperties.Auth sharedAuth() {
        return new BrainClientProperties.Auth(BrainClientProperties.AuthMode.SHARED_TOKEN, null, TOKEN);
    }

    private static String frame(String json) throws IOException {
        return "event: " + JSON.readTree(json).get("type").asText() + "\ndata: " + json + "\n\n";
    }

    private static String storedTerminal(String operation) {
        return InternalRunFixtures.load("operations/" + operation + ".json").get("response").get("terminal").toString();
    }

    private static String activity(String type, String suffix) {
        return "{\"contract_version\":\"brain-source-v1\",\"run_id\":\"run-441\",\"attempt\":1,"
                + "\"fence_token\":\"fence-a1b2\",\"source_event_id\":\"run-441:activity-1:" + suffix + "\","
                + "\"occurred_at\":\"2026-10-02T09:00:01Z\",\"type\":\"" + type + "\","
                + "\"payload\":{\"kind\":\"input_analysis\",\"activity_id\":\"activity-1\"}}";
    }
}
