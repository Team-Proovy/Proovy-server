package com.proovy.integration.brain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;

/**
 * proovy-brain {@code testkit/}(PR #150, squash 7b8608e)의 supervisor·control API client. Server 연동 테스트가 실제 Python
 * 내부 API child를 띄우고(같은 제품 DB에 Brain schema bootstrap), request별 scenario·fault를 걸고, 받은 호출·실행 수를 읽는다.
 *
 * <p>환경변수(모두 있어야 실행한다):
 * <ul>
 *   <li>{@code PROOVY_BRAIN_TESTKIT_URL} — supervisor, 예 {@code http://127.0.0.1:26081}</li>
 *   <li>{@code PROOVY_BRAIN_TESTKIT_CONTROL_TOKEN}, {@code PROOVY_BRAIN_TESTKIT_SHARED_TOKEN}</li>
 *   <li>{@code PROOVY_BRAIN_TESTKIT_RUNTIME_PASSWORD} — Brain {@code brain_runtime} role(역할 격리 검사용)</li>
 *   <li>{@code PROOVY_REQUIRE_BRAIN_IT=true} — 미구성 시 skip이 아니라 실패</li>
 * </ul>
 * proovy-brain이 private이라 Server CI는 Brain을 checkout할 수 없다(사용자 결정: CI skip, 로컬 스크립트
 * {@code scripts/brain-integration/run-local.sh}로 실행). 미구성 skip은 "NOT EXECUTED"로 로그에 남아 통과로 보이지 않는다.
 */
final class BrainTestkit {

    static final String SKIP_REASON = "NOT EXECUTED — real Brain integration (task 1.87) needs a running proovy-brain "
            + "testkit (PROOVY_BRAIN_TESTKIT_URL/_CONTROL_TOKEN/_SHARED_TOKEN/_RUNTIME_PASSWORD). proovy-brain is private, "
            + "so Server CI skips it by decision; run scripts/brain-integration/run-local.sh";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CONTROL_HEADER = "X-Testkit-Control-Token";

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final String supervisorUrl;
    private final String controlToken;
    private final String sharedToken;
    private final String runtimePassword;
    private String apiUrl;

    private BrainTestkit(String supervisorUrl, String controlToken, String sharedToken, String runtimePassword) {
        this.supervisorUrl = supervisorUrl;
        this.controlToken = controlToken;
        this.sharedToken = sharedToken;
        this.runtimePassword = runtimePassword;
    }

    /** 환경이 없으면 skip(이유 명시) 또는 실패(PROOVY_REQUIRE_BRAIN_IT=true). */
    static BrainTestkit fromEnvironment() {
        String url = System.getenv("PROOVY_BRAIN_TESTKIT_URL");
        String control = System.getenv("PROOVY_BRAIN_TESTKIT_CONTROL_TOKEN");
        String shared = System.getenv("PROOVY_BRAIN_TESTKIT_SHARED_TOKEN");
        String runtime = System.getenv("PROOVY_BRAIN_TESTKIT_RUNTIME_PASSWORD");
        boolean configured = url != null && control != null && shared != null && runtime != null;
        if (!configured && Boolean.parseBoolean(System.getenv("PROOVY_REQUIRE_BRAIN_IT"))) {
            throw new IllegalStateException("PROOVY_REQUIRE_BRAIN_IT=true but the Brain testkit is not configured");
        }
        if (!configured) {
            System.err.println("[brain-integration] " + SKIP_REASON);
        }
        Assumptions.assumeTrue(configured, SKIP_REASON);
        return new BrainTestkit(url, control, shared, runtime);
    }

    String sharedToken() {
        return sharedToken;
    }

    String runtimePassword() {
        return runtimePassword;
    }

    String apiUrl() {
        return apiUrl;
    }

    // -- supervisor ----------------------------------------------------------

    /** 그 database에 Brain schema를 bootstrap·migrate하고 API child를 띄운다. */
    JsonNode start(String database, int leaseTtlSeconds) {
        JsonNode status = lifecycle("/brain/start",
                JSON.createObjectNode().put("database", database).put("lease_ttl_seconds", leaseTtlSeconds));
        apiUrl = status.get("api_url").asText();
        return status;
    }

    JsonNode restart(String signal) {
        return lifecycle("/brain/restart", JSON.createObjectNode().put("signal", signal));
    }

    /**
     * start·restart. supervisor는 child readiness를 고정 60초만 기다리고 실패하면 child를 죽인다 — 부하가 큰 공용 호스트에서
     * 그 시간을 넘긴 경우만 같은 요청을 다시 보낸다(최대 3번). 다른 실패는 바로 던진다.
     */
    private JsonNode lifecycle(String path, JsonNode body) {
        IllegalStateException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return send("POST", supervisorUrl + path, body, 200);
            } catch (IllegalStateException e) {
                if (!String.valueOf(e.getMessage()).contains("did not become ready")) {
                    throw e;
                }
                System.err.println("[brain-integration] " + path + " readiness timeout, retry " + attempt);
                last = e;
            }
        }
        throw last;
    }

    JsonNode stop() {
        return send("POST", supervisorUrl + "/brain/stop", JSON.createObjectNode().put("signal", "kill"), 200);
    }

    JsonNode status() {
        return send("GET", supervisorUrl + "/brain/status", null, 200);
    }

    // -- child control ------------------------------------------------------

    JsonNode info() {
        return send("GET", apiUrl + "/__testkit__/v1/info", null, 200);
    }

    void reset() {
        send("POST", apiUrl + "/__testkit__/v1/reset", null, 200);
    }

    void plan(String requestId, String scenario, ObjectNode faults) {
        ObjectNode body = JSON.createObjectNode().put("scenario", scenario);
        body.set("faults", faults == null ? JSON.createObjectNode() : faults);
        send("PUT", apiUrl + "/__testkit__/v1/plans/" + requestId, body, 200);
    }

    void release(String requestId, String point) {
        ObjectNode body = point == null ? null : JSON.createObjectNode().put("point", point);
        send("POST", apiUrl + "/__testkit__/v1/plans/" + requestId + "/release", body, 200);
    }

    void advanceClock(double seconds) {
        send("POST", apiUrl + "/__testkit__/v1/clock", JSON.createObjectNode().put("advance_seconds", seconds), 200);
    }

    List<JsonNode> observations(String requestId) {
        return list(send("GET", apiUrl + "/__testkit__/v1/observations?request_id=" + requestId, null, 200));
    }

    List<JsonNode> executions(String requestId) {
        return list(send("GET", apiUrl + "/__testkit__/v1/executions?request_id=" + requestId, null, 200));
    }

    static ObjectNode faults() {
        return JSON.createObjectNode();
    }

    // -- raw internal API (서비스 인증 거부 검사) ------------------------------------

    int internalStatus(String runId, String authorization) {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create(apiUrl + "/internal/v1/runs/" + runId + "?attempt=1&fence_token=probe"))
                .timeout(Duration.ofSeconds(10)).GET();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            throw new IllegalStateException("internal API probe failed", e);
        }
    }

    JsonNode internalRunStatus(String runId, int attempt, String fence) {
        return send("GET", apiUrl + "/internal/v1/runs/" + runId + "?attempt=" + attempt + "&fence_token=" + fence,
                null, 200, "Bearer " + sharedToken);
    }

    private JsonNode send(String method, String url, JsonNode body, int expected) {
        return send(method, url, body, expected, null);
    }

    private JsonNode send(String method, String url, JsonNode body, int expected, String authorization) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120));
        if (authorization != null) {
            request.header("Authorization", authorization);
        } else {
            request.header(CONTROL_HEADER, controlToken);
        }
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body.toString());
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        request.method(method, publisher);
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != expected) {
                throw new IllegalStateException(method + " " + url + " -> " + response.statusCode() + " "
                        + response.body());
            }
            return response.body().isBlank() ? JSON.nullNode() : JSON.readTree(response.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(method + " " + url + " failed", e);
        }
    }

    private static List<JsonNode> list(JsonNode array) {
        List<JsonNode> items = new ArrayList<>();
        array.forEach(items::add);
        return items;
    }
}
