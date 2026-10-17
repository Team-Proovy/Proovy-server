package com.proovy.global.infra.brain;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** loopback 임의 port의 가짜 Brain. 받은 요청을 기록하고 테스트가 정한 handler로 응답한다. */
final class FakeBrainServer implements AutoCloseable {

    @FunctionalInterface
    interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }

    record Received(String method, String path, String query, Headers headers, String body) {
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile Handler handler = exchange -> respond(exchange, 500, "{}");

    FakeBrainServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                    exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders(), body));
            try {
                handler.handle(exchange);
            } catch (Exception ignored) {
                // 연결이 끊긴 경우 등 — 테스트는 기록과 latch로 판정한다.
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    void handle(Handler next) {
        handler = next;
    }

    List<Received> received() {
        return received;
    }

    static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** SSE 응답 header를 보내고 body stream을 연다(chunked). */
    static OutputStream openSse(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        return exchange.getResponseBody();
    }

    static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
