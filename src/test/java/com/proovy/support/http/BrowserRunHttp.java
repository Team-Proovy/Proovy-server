package com.proovy.support.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/** 브라우저 run API(browser-run-v1)를 실제 HTTP로 부르는 테스트 도구. 사용자 JWT는 호출마다 받는다. */
public final class BrowserRunHttp {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final int port;
    private final Duration wait;

    public BrowserRunHttp(int port, Duration wait) {
        this.port = port;
        this.wait = wait;
    }

    public record Response(int status, JsonNode body) {
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    /** POST /api/conversations (접수 + live SSE). */
    public SseClient post(String token, String body) throws Exception {
        return SseClient.open(http, HttpRequest.newBuilder(uri("/api/conversations"))
                .header("Authorization", "Bearer " + token).header("Accept", "text/event-stream")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                wait);
    }

    /** GET /api/ai-runs/{runId}/events (replay/live). {@code lastEventId}는 header로 보낸다. */
    public SseClient events(String token, UUID runId, String lastEventId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/api/ai-runs/" + runId + "/events"))
                .header("Authorization", "Bearer " + token).header("Accept", "text/event-stream").GET();
        if (lastEventId != null) {
            request.header("Last-Event-ID", lastEventId);
        }
        return SseClient.open(http, request.build(), wait);
    }

    public Response get(String token, String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(15)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), parse(response.body()));
    }

    public Response cancel(String token, UUID runId) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/api/ai-runs/" + runId + "/cancel"))
                .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), parse(response.body()));
    }

    public Response conversation(String token, long noteId) throws Exception {
        return get(token, "/api/conversations/" + noteId + "/messages");
    }

    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return JSON.nullNode();
        }
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            return JSON.getNodeFactory().textNode(body);
        }
    }
}
