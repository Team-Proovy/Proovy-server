package com.proovy.domain.run.coordinator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proovy.domain.run.brain.InternalRunRequest;
import com.proovy.domain.run.brain.InternalRunWire;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 테스트용 Brain 내부 API(internal-run-v1)의 HTTP 가짜. 실제 {@code BrainRunClient}가 loopback 임시 port로 부른다.
 * stream 응답은 request_id별 각본({@link Step})을 순서대로 쓰고 마지막 단계를 반복한다. 기본은 activity 하나 + pin된
 * terminal-v2 clarification terminal이다. status 응답은 run별 {@link StatusMode}다.
 */
final class FakeBrain implements AutoCloseable {

    enum Step {
        /** activity.started + clarification run.completed */
        COMPLETE,
        /** activity.started 뒤 {@link #release}까지 붙잡았다가 terminal */
        HOLD,
        /** activity.started 뒤 terminal 없이 정상 종료 */
        END_WITHOUT_TERMINAL,
        /** 503 capacity (record 생성 전 거부) */
        CAPACITY,
        /** 400 invalid_request */
        BAD_REQUEST,
        /** 검증된 substantive terminal이지만 run scope 밖 asset을 인용한다(수락 거부) */
        OUT_OF_SCOPE_TERMINAL
    }

    enum StatusMode {
        ABSENT,
        EXECUTING,
        TERMINAL
    }

    record Delivery(String runId, String requestId, int attempt, String fenceToken, String deadline, JsonNode usage,
                    String fingerprint) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path MIRROR = mirror();

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, List<Step>> scripts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> cursor = new ConcurrentHashMap<>();
    private final Map<String, StatusMode> statusModes = new ConcurrentHashMap<>();
    private final Map<String, Delivery> lastByRun = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> holds = new ConcurrentHashMap<>();
    private final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
    private final List<String> statusCalls = new CopyOnWriteArrayList<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();

    FakeBrain() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(executor);
        server.createContext("/internal/v1/runs/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void script(String requestId, Step... steps) {
        scripts.put(requestId, List.of(steps));
    }

    void status(String runId, StatusMode mode) {
        statusModes.put(runId, mode);
    }

    void release(String requestId) {
        holds.computeIfAbsent(requestId, key -> new CountDownLatch(1)).countDown();
    }

    List<Delivery> deliveries(String requestId) {
        return deliveries.stream().filter(d -> d.requestId().equals(requestId)).toList();
    }

    List<Delivery> deliveries() {
        return List.copyOf(deliveries);
    }

    long statusCalls(String runId) {
        return statusCalls.stream().filter(runId::equals).count();
    }

    int maxInFlight() {
        return maxInFlight.get();
    }

    void resetInFlightPeak() {
        maxInFlight.set(inFlight.get());
    }

    @Override
    public void close() {
        holds.values().forEach(CountDownLatch::countDown);
        server.stop(0);
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String[] path = exchange.getRequestURI().getPath().split("/");
            // /internal/v1/runs/{runId}[/stream]
            String runId = path[4];
            if ("GET".equals(exchange.getRequestMethod())) {
                statusCalls.add(runId);
                json(exchange, 200, statusResponse(runId));
                return;
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (path.length == 6 && "stream".equals(path[5])) {
                stream(exchange, runId, body);
                return;
            }
            json(exchange, 404, JSON.createObjectNode().put("error", "not_found"));
        } finally {
            exchange.close();
        }
    }

    private void stream(HttpExchange exchange, String runId, byte[] body) throws IOException {
        JsonNode wire = JSON.readTree(body);
        InternalRunRequest request = InternalRunWire.read(body, InternalRunRequest.class);
        Delivery delivery = new Delivery(runId, wire.get("request_id").asText(), wire.get("attempt").asInt(),
                wire.get("fence_token").asText(), wire.at("/execution_options/request_deadline_at").asText(),
                wire.at("/execution_options/usage"), request.fingerprint().sha256());
        deliveries.add(delivery);
        lastByRun.put(runId, delivery);
        Step step = next(delivery.requestId());
        switch (step) {
            case CAPACITY -> json(exchange, 503, JSON.createObjectNode().put("error", "unavailable")
                    .put("reason", "capacity"));
            case BAD_REQUEST -> json(exchange, 400, JSON.createObjectNode().put("error", "invalid_request"));
            default -> sse(exchange, delivery, step);
        }
    }

    private void sse(HttpExchange exchange, Delivery delivery, Step step) throws IOException {
        int now = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(now, Math::max);
        try {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            frame(out, bind(load("activity-v1/valid/input-analysis-started.json"), delivery, "activity-1:started"));
            switch (step) {
                case HOLD -> {
                    CountDownLatch hold = holds.computeIfAbsent(delivery.requestId(), key -> new CountDownLatch(1));
                    // keep-alive comment로 idle 상한을 넘기지 않는다
                    while (!hold.await(500, TimeUnit.MILLISECONDS)) {
                        out.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    frame(out, terminal(delivery));
                }
                case END_WITHOUT_TERMINAL -> { }
                case OUT_OF_SCOPE_TERMINAL -> {
                    JsonNode source = load("terminal-v2/pairs/direct-verified.json").get("source");
                    frame(out, bind(source, delivery, "terminal"));
                }
                default -> frame(out, terminal(delivery));
            }
            out.flush();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException disconnected) {
            // client가 구독을 끊었다
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private ObjectNode statusResponse(String runId) {
        ObjectNode response = JSON.createObjectNode().put("contract_version", "internal-run-v1").put("run_id", runId);
        StatusMode mode = statusModes.getOrDefault(runId, StatusMode.ABSENT);
        Delivery last = lastByRun.get(runId);
        if (mode == StatusMode.ABSENT || last == null) {
            return response.put("state", "absent");
        }
        response.put("state", mode == StatusMode.EXECUTING ? "executing" : "terminal")
                .put("attempt", last.attempt()).put("fence_token", last.fenceToken())
                .put("request_fingerprint", last.fingerprint());
        if (mode == StatusMode.TERMINAL) {
            response.set("terminal", terminal(last));
        }
        return response;
    }

    private Step next(String requestId) {
        List<Step> steps = scripts.getOrDefault(requestId, List.of(Step.COMPLETE));
        int index = cursor.computeIfAbsent(requestId, key -> new AtomicInteger()).getAndIncrement();
        return steps.get(Math.min(index, steps.size() - 1));
    }

    private static JsonNode terminal(Delivery delivery) {
        return bind(load("terminal-v2/pairs/clarification.json").get("source"), delivery, "terminal");
    }

    private static ObjectNode bind(JsonNode source, Delivery delivery, String suffix) {
        ObjectNode bound = source.deepCopy();
        bound.put("run_id", delivery.runId()).put("attempt", delivery.attempt())
                .put("fence_token", delivery.fenceToken())
                .put("source_event_id", delivery.runId() + ":" + suffix)
                .put("occurred_at", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
        return bound;
    }

    private static void frame(OutputStream out, JsonNode event) throws IOException {
        String text = "event: " + event.get("type").asText() + "\ndata: " + JSON.writeValueAsString(event) + "\n\n";
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void json(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static JsonNode load(String relative) {
        try (InputStream in = Files.newInputStream(MIRROR.resolve(relative))) {
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path mirror() {
        try {
            return Path.of(FakeBrain.class.getClassLoader().getResource("contracts/brain").toURI());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static List<String> requestIds(List<Delivery> deliveries) {
        List<String> ids = new ArrayList<>();
        deliveries.forEach(d -> ids.add(d.requestId()));
        return ids;
    }
}
