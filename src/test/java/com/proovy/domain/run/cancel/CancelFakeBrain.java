package com.proovy.domain.run.cancel;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 취소 테스트용 Brain 내부 API(internal-run-v1) HTTP 가짜. 실제 {@code BrainRunClient}가 loopback 임시 port로 부른다.
 * <ul>
 *   <li>stream: {@code activity.started}(activity-1) 뒤 cancel이 올 때까지 keep-alive로 붙잡는다. cancel을 받으면 열린 Activity의
 *       {@code activity.canceled}를 보내고 terminal 없이 끝낸다(Brain wind-down). {@link #complete}면 clarification terminal.</li>
 *   <li>cancel: run별 {@link CancelMode}. 기본 {@code CONFIRM}은 첫 호출에 accepted/canceling, 그 뒤 already_terminal/terminal.</li>
 *   <li>status: record 상태대로 absent·executing·canceling·terminal(run.canceled 또는 clarification).</li>
 * </ul>
 */
final class CancelFakeBrain implements AutoCloseable {

    enum CancelMode {
        /** 첫 호출 accepted(canceling), 정리가 끝난 뒤 already_terminal(terminal) */
        CONFIRM,
        /** 정리를 끝내지 않는다 — 언제나 canceling */
        NEVER_CONFIRM,
        /** record 없음 — unknown_run(absent) */
        ABSENT,
        /** 503 */
        UNAVAILABLE,
        /** 응답하지 않는다(close까지) — hang인 Brain */
        HANG
    }

    enum Record {
        ABSENT,
        EXECUTING,
        CANCELING,
        CANCELED,
        COMPLETED
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path MIRROR = mirror();

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, CancelMode> modes = new ConcurrentHashMap<>();
    private final Map<String, Record> records = new ConcurrentHashMap<>();
    private final Map<String, JsonNode> lastRequest = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> windDown = new ConcurrentHashMap<>();
    private final Map<String, Boolean> completeOnRelease = new ConcurrentHashMap<>();
    private final Map<String, Boolean> open = new ConcurrentHashMap<>();
    private final List<String> streamCalls = new CopyOnWriteArrayList<>();
    private final List<JsonNode> cancelCalls = new CopyOnWriteArrayList<>();
    private final Map<String, Runnable> beforeCancelAck = new ConcurrentHashMap<>();
    private final CountDownLatch closed = new CountDownLatch(1);

    CancelFakeBrain() {
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

    void mode(String runId, CancelMode mode) {
        modes.put(runId, mode);
    }

    /** stream 없이 Brain record가 실행 중인 것처럼 둔다(coordinator 없이 RUNNING run을 만든 테스트). */
    void executing(String runId, int attempt, String fenceToken) {
        lastRequest.put(runId, JSON.createObjectNode().put("run_id", runId).put("attempt", attempt)
                .put("fence_token", fenceToken).put("_fingerprint", "f".repeat(64)));
        records.put(runId, Record.EXECUTING);
    }

    /** 붙잡은 stream을 clarification terminal로 끝낸다(취소 전 완료 경합용). */
    void complete(String runId) {
        completeOnRelease.put(runId, true);
        latch(runId).countDown();
    }

    /** 다음 cancel 호출 하나가 응답하기 직전에 한 번 실행한다(Brain 호출 중의 Server 쪽 변화 재현). */
    void beforeNextCancelAck(String runId, Runnable action) {
        beforeCancelAck.put(runId, action);
    }

    long streamCalls(String runId) {
        return streamCalls.stream().filter(runId::equals).count();
    }

    List<JsonNode> cancelCalls(String runId) {
        return cancelCalls.stream().filter(call -> runId.equals(call.get("run_id").asText())).toList();
    }

    Record record(String runId) {
        return records.getOrDefault(runId, Record.ABSENT);
    }

    @Override
    public void close() {
        closed.countDown();
        windDown.values().forEach(CountDownLatch::countDown);
        server.stop(0);
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------

    private CountDownLatch latch(String runId) {
        return windDown.computeIfAbsent(runId, key -> new CountDownLatch(1));
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String[] path = exchange.getRequestURI().getPath().split("/");
            String runId = path[4];
            if ("GET".equals(exchange.getRequestMethod())) {
                json(exchange, 200, status(runId));
                return;
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (path.length == 6 && "stream".equals(path[5])) {
                stream(exchange, runId, body);
            } else if (path.length == 6 && "cancel".equals(path[5])) {
                cancel(exchange, runId, JSON.readTree(body));
            } else {
                json(exchange, 404, JSON.createObjectNode().put("error", "not_found"));
            }
        } finally {
            exchange.close();
        }
    }

    private void stream(HttpExchange exchange, String runId, byte[] body) throws IOException {
        streamCalls.add(runId);
        JsonNode wire = JSON.readTree(body);
        InternalRunRequest request = InternalRunWire.read(body, InternalRunRequest.class);
        ((ObjectNode) wire).put("_fingerprint", request.fingerprint().sha256());
        lastRequest.put(runId, wire);
        records.putIfAbsent(runId, Record.EXECUTING);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        open.put(runId, true);
        try {
            if (records.get(runId) == Record.EXECUTING) {
                frame(out, bind(load("activity-v1/valid/input-analysis-started.json"), wire, "activity-1:started"));
            }
            CountDownLatch latch = latch(runId);
            while (!latch.await(300, TimeUnit.MILLISECONDS)) {
                out.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            if (Boolean.TRUE.equals(completeOnRelease.get(runId)) && records.get(runId) == Record.EXECUTING) {
                records.put(runId, Record.COMPLETED);
                frame(out, clarification(wire));
            } else if (records.get(runId) == Record.CANCELING) {
                ObjectNode canceled = bind(load("activity-v1/valid/input-analysis-started.json"), wire,
                        "activity-1:canceled");
                canceled.put("type", "activity.canceled");
                canceled.set("payload", JSON.createObjectNode().put("kind", "input_analysis")
                        .put("activity_id", "activity-1"));
                frame(out, canceled);
                // 정리를 끝내고 run.canceled를 저장한 뒤 terminal 없이 stream을 닫는다
                if (modes.getOrDefault(runId, CancelMode.CONFIRM) == CancelMode.CONFIRM) {
                    records.put(runId, Record.CANCELED);
                }
            }
            out.flush();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException disconnected) {
            // client가 구독을 끊었다
        } finally {
            open.remove(runId);
        }
    }

    private void cancel(HttpExchange exchange, String runId, JsonNode request) throws IOException {
        cancelCalls.add(request);
        CancelMode mode = modes.getOrDefault(runId, CancelMode.CONFIRM);
        if (mode == CancelMode.HANG) {
            try {
                closed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return;
        }
        Runnable before = beforeCancelAck.remove(runId);
        if (before != null) {
            before.run();
        }
        ObjectNode ack = JSON.createObjectNode().put("contract_version", "internal-run-v1").put("run_id", runId);
        switch (mode) {
            case UNAVAILABLE -> {
                json(exchange, 503, JSON.createObjectNode().put("error", "unavailable"));
                return;
            }
            case ABSENT -> ack.put("outcome", "unknown_run").put("state", "absent");
            default -> {
                Record record = records.getOrDefault(runId, Record.ABSENT);
                switch (record) {
                    case ABSENT -> ack.put("outcome", "unknown_run").put("state", "absent");
                    case EXECUTING -> {
                        records.put(runId, Record.CANCELING);
                        latch(runId).countDown();
                        ack.put("outcome", "accepted").put("state", "canceling");
                    }
                    case CANCELING -> {
                        if (mode == CancelMode.CONFIRM && !open.containsKey(runId)) {
                            // stream 없는 실행은 다음 확인 때 정리가 끝나 있다
                            records.put(runId, Record.CANCELED);
                            ack.put("outcome", "already_terminal").put("state", "terminal");
                        } else {
                            ack.put("outcome", "already_canceling").put("state", "canceling");
                        }
                    }
                    case CANCELED, COMPLETED -> ack.put("outcome", "already_terminal").put("state", "terminal");
                }
            }
        }
        json(exchange, 200, ack);
    }

    private ObjectNode status(String runId) {
        ObjectNode response = JSON.createObjectNode().put("contract_version", "internal-run-v1").put("run_id", runId);
        JsonNode last = lastRequest.get(runId);
        Record record = records.getOrDefault(runId, Record.ABSENT);
        if (last == null || record == Record.ABSENT) {
            return response.put("state", "absent");
        }
        response.put("attempt", last.get("attempt").asInt()).put("fence_token", last.get("fence_token").asText())
                .put("request_fingerprint", last.get("_fingerprint").asText());
        switch (record) {
            case EXECUTING -> response.put("state", "executing");
            case CANCELING -> response.put("state", "canceling");
            case CANCELED -> {
                ObjectNode terminal = bind(JSON.createObjectNode(), last, "terminal");
                terminal.put("contract_version", "brain-source-v1").put("type", "run.canceled");
                terminal.set("payload", JSON.createObjectNode().put("cause", "user_requested"));
                response.put("state", "terminal").set("terminal", terminal);
            }
            case COMPLETED -> response.put("state", "terminal").set("terminal", clarification(last));
            default -> throw new IllegalStateException();
        }
        return response;
    }

    private static ObjectNode clarification(JsonNode request) {
        return bind(load("terminal-v2/pairs/clarification.json").get("source"), request, "terminal");
    }

    private static ObjectNode bind(JsonNode source, JsonNode request, String suffix) {
        ObjectNode bound = source.deepCopy();
        String runId = request.get("run_id").asText();
        bound.put("run_id", runId).put("attempt", request.get("attempt").asInt())
                .put("fence_token", request.get("fence_token").asText())
                .put("source_event_id", runId + ":" + suffix)
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
            return Path.of(CancelFakeBrain.class.getClassLoader().getResource("contracts/brain").toURI());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
