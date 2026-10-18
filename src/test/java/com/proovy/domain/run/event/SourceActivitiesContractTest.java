package com.proovy.domain.run.event;

import static com.proovy.domain.run.event.ActivityFixtures.FIXTURES;
import static com.proovy.domain.run.event.ActivityFixtures.MIRROR;
import static com.proovy.domain.run.event.ActivityFixtures.RAW;
import static com.proovy.domain.run.event.ActivityFixtures.raw;
import static com.proovy.domain.run.event.ActivityFixtures.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proovy.domain.run.brain.InternalRunContractViolation;
import com.proovy.domain.run.brain.InternalRunWire;
import com.proovy.domain.run.brain.SourceEvent;
import com.proovy.domain.run.brain.SourceEventType;
import com.proovy.domain.run.contract.BrowserRunContract;
import com.proovy.domain.run.entity.AiRunEventType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * activity-v1 fixture(pin 303a9e4)로 source→durable 변환을 Brain {@code parse_source_event}·
 * {@code to_browser_event_payload}와 대조한다. browser-run-v1 stream의 activity frame도 같은 변환을 통과해야 한다.
 */
class SourceActivitiesContractTest {

    private static final String LEAK_SENTINEL = "DO-NOT-LEAK";

    private static SourceEvent source(String json) {
        return InternalRunWire.read(json, SourceEvent.class);
    }

    private static ObjectNode convert(SourceEvent event) {
        return SourceActivities.browserPayload(event.type(), event.payload());
    }

    // ------------------------------------------------------------------
    // pin된 fixture
    // ------------------------------------------------------------------

    @Test
    @DisplayName("valid/ 모든 source event가 통과하고 camelCase payload는 같은 값을 보존한다")
    void validFixturesConvert() {
        List<Map.Entry<String, String>> valid = raw(FIXTURES.resolve("valid"));
        assertThat(valid).hasSize(13);
        for (Map.Entry<String, String> fixture : valid) {
            SourceEvent event = source(fixture.getValue());
            ObjectNode payload = convert(event);
            // camelCase → snake_case로 되돌리면 원본 payload와 같다(필드 추가·누락 없음)
            JsonNode original = read(FIXTURES.resolve("valid/" + fixture.getKey() + ".json")).get("payload");
            assertThat(snake(payload)).as(fixture.getKey()).isEqualTo(original);
        }
    }

    @Test
    @DisplayName("browser/ golden: 같은 activityId·kind와 camelCase payload, source 전용 필드 없음")
    void browserGoldens() {
        for (Map.Entry<String, String> golden : raw(FIXTURES.resolve("browser"))) {
            String sourceName = golden.getKey().replace(".browser", "");
            SourceEvent event = source(ActivityFixtures.text(FIXTURES.resolve("valid/" + sourceName + ".json")));
            JsonNode expected = read(FIXTURES.resolve("browser/" + golden.getKey() + ".json"));

            assertThat(convert(event)).as(sourceName).isEqualTo(expected.get("payload"));
            assertThat(expected.get("type").asText()).isEqualTo(event.type().wireValue());
            assertThat(expected.get("runId").asText()).isEqualTo(event.runId());
            assertThat(expected.get("contractVersion").asText()).isEqualTo(BrowserRunContract.FRAME_CONTRACT_VERSION);
            assertThat(Instant.parse(expected.get("occurredAt").asText())).isEqualTo(event.occurredAt().toInstant());
            assertThat(expected.has("attempt") || expected.has("fenceToken") || expected.has("sourceEventId"))
                    .isFalse();
        }
    }

    @Test
    @DisplayName("invalid/ 모든 source event는 envelope 또는 activity 단계에서 거부되고 오류에 입력 값이 없다")
    void invalidFixturesRejected() {
        List<Map.Entry<String, String>> invalid = raw(FIXTURES.resolve("invalid"));
        assertThat(invalid).hasSize(22);
        for (Map.Entry<String, String> fixture : invalid) {
            Throwable error = catchThrowable(() -> convert(source(fixture.getValue())));
            assertThat(error).as(fixture.getKey())
                    .isInstanceOfAny(InternalRunContractViolation.class, ActivityContractViolation.class);
            assertThat(error.getMessage()).as(fixture.getKey()).doesNotContain(LEAK_SENTINEL);
        }
    }

    @Test
    @DisplayName("금지 내용 fixture는 activity 단계 자체에서 거부된다(envelope 검사에 기대지 않는다)")
    void forbiddenContentRejectedByActivityLayer() {
        for (String name : new String[]{"code-field-in-payload", "stdout-in-payload", "verification-step-verdict",
                "verification-answer-leak", "fake-percent", "unknown-label", "wrong-kind-label", "url-in-message",
                "control-char-in-message", "bad-activity-id", "type-payload-mismatch", "run-event-type"}) {
            // envelope 검사(source_event_id 형식 등)를 통과시켜 activity 단계만 본다
            ObjectNode raw = (ObjectNode) read(FIXTURES.resolve("invalid/" + name + ".json"));
            raw.put("source_event_id", "run-7:fixture:" + name);
            SourceEvent event = source(raw.toString());
            assertThatThrownBy(() -> convert(event)).as(name).isInstanceOf(ActivityContractViolation.class)
                    .message().doesNotContain(LEAK_SENTINEL);
        }
    }

    @Test
    @DisplayName("browser-run-v1 stream의 모든 activity frame payload가 같은 변환을 왕복한다")
    void browserRunStreamActivityFramesRoundTrip() throws IOException {
        int frames = 0;
        try (Stream<Path> streams = Files.list(MIRROR.resolve("browser-run-v1/streams/valid"))) {
            for (Path stream : streams.sorted().toList()) {
                for (JsonNode frame : read(stream).get("frames")) {
                    String type = frame.get("type").asText();
                    if (!type.startsWith("activity.")) {
                        continue;
                    }
                    SourceEventType sourceType = SourceEventType.valueOf(
                            type.replace('.', '_').toUpperCase());
                    assertThat(SourceActivities.browserPayload(sourceType, snake(frame.get("payload"))))
                            .as(stream.getFileName() + " seq " + frame.get("seq"))
                            .isEqualTo(frame.get("payload"));
                    frames++;
                }
            }
        }
        assertThat(frames).isPositive();
    }

    // ------------------------------------------------------------------
    // envelope → durable
    // ------------------------------------------------------------------

    @Test
    @DisplayName("toDurable은 Server run UUID·activity 타입·µs 시각을 고정하고 source 전용 값을 payload에 넣지 않는다")
    void toDurable() {
        UUID runId = UUID.randomUUID();
        ObjectNode envelope = envelope(runId.toString(), "activity.started", started("verification")
                .put("label", "verifying_steps").put("method_label", "SymPy 동치 검사"));
        envelope.put("occurred_at", "2026-10-17T09:00:01.123456789+09:00");
        DurableActivity activity = SourceActivities.toDurable(source(envelope.toString()));

        assertThat(activity.runId()).isEqualTo(runId);
        assertThat(activity.type()).isEqualTo(AiRunEventType.ACTIVITY_STARTED);
        assertThat(activity.kind()).isEqualTo(ActivityKind.VERIFICATION);
        assertThat(activity.activityId()).isEqualTo("activity-1");
        assertThat(activity.attempt()).isEqualTo(1);
        assertThat(activity.fenceToken()).isEqualTo("fence-1");
        assertThat(activity.occurredAt()).isEqualTo(Instant.parse("2026-10-17T00:00:01.123456Z"));
        assertThat(activity.payload().toString())
                .doesNotContain("fence").doesNotContain("attempt").doesNotContain("source_event");
    }

    @Test
    @DisplayName("run_id가 Server run UUID가 아니면 거부한다")
    void nonUuidRunIdRejected() {
        SourceEvent event = source(envelope("run-7", "activity.started",
                started("input_analysis").put("label", "analyzing_input")).toString());
        assertThatThrownBy(() -> SourceActivities.toDurable(event)).isInstanceOf(ActivityContractViolation.class);
    }

    @Test
    @DisplayName("run.completed·run.failed는 activity 저장 경로가 아니다")
    void terminalTypesRejected() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        assertThatThrownBy(() -> SourceActivities.browserPayload(SourceEventType.RUN_COMPLETED, payload))
                .isInstanceOf(ActivityContractViolation.class);
        assertThatThrownBy(() -> SourceActivities.browserPayload(SourceEventType.RUN_FAILED, payload))
                .isInstanceOf(ActivityContractViolation.class);
    }

    @Test
    @DisplayName("저장 한도(jsonb text 16384 byte)를 넘는 payload는 seq 배정 전에 거부한다")
    void oversizedPayloadRejected() {
        ObjectNode payload = completed("note_retrieval").put("match_status", "conflicting_content");
        ArrayNode pages = payload.putArray("pages");
        String assetId = "a".repeat(128);
        for (int page = 1; page <= SourceActivities.MAX_PAGES; page++) {
            pages.addObject().put("asset_id", assetId).put("page", page);
        }
        SourceEvent event = source(envelope(UUID.randomUUID().toString(), "activity.completed", payload).toString());
        assertThat(convert(event).get("pages")).hasSize(SourceActivities.MAX_PAGES);
        assertThatThrownBy(() -> SourceActivities.toDurable(event)).isInstanceOf(ActivityContractViolation.class)
                .hasMessage("payload is too large to store");
    }

    @Test
    @DisplayName("저장 크기 계산은 Postgres jsonb::text와 같은 구분자·escape 규칙을 쓴다")
    void storedLengthMatchesJsonbText() throws IOException {
        JsonNode node = RAW.readTree("{\"a\":1,\"b\":[1,2],\"c\":\"q\\\"\\\\ 가\",\"d\":{},\"e\":[],\"f\":null,\"g\":true}");
        // {"a": 1, "b": [1, 2], "c": "q\"\\ 가", "d": {}, "e": [], "f": null, "g": true}
        String rendered = "{\"a\": 1, \"b\": [1, 2], \"c\": \"q\\\"\\\\ 가\", \"d\": {}, \"e\": [], \"f\": null, \"g\": true}";
        assertThat(SourceActivities.storedLength(node))
                .isEqualTo(rendered.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    // ------------------------------------------------------------------
    // 세부 규칙 (Brain activity.py와 같은 경계)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("문자열은 Python strip 집합으로 양끝을 지운 값을 저장하고 길이는 code point로 센다")
    void stripsAndCountsCodePoints() {
        ObjectNode updated = updated("input_analysis").put("message", "\u00a0 입력을 읽고 있다\u202f");
        assertThat(convert(updated, "activity.updated").get("message").asText()).isEqualTo("입력을 읽고 있다");

        ObjectNode atLimit = updated("input_analysis").put("message", "\uD835\uDC65".repeat(500));
        assertThat(convert(atLimit, "activity.updated").get("message").asText()).hasSize(1000);
        expectViolation(updated("input_analysis").put("message", "\uD835\uDC65".repeat(501)), "activity.updated");
        expectViolation(updated("input_analysis").put("message", " \u3000 "), "activity.updated");

        ObjectNode spacedId = started("input_analysis").put("label", "analyzing_input")
                .put("activity_id", " activity-7 ");
        assertThat(convert(spacedId, "activity.started").get("activityId").asText()).isEqualTo("activity-7");
    }

    @Test
    @DisplayName("enum·label·kind는 strip하지 않고 정확히 일치해야 한다")
    void enumsAreExact() {
        expectViolation(started("input_analysis").put("label", " analyzing_input"), "activity.started");
        expectViolation(updated("input_analysis").put("status", "IN_PROGRESS"), "activity.updated");
        expectViolation(started(" input_analysis").put("label", "analyzing_input"), "activity.started");
        expectViolation(started("unknown_kind").put("label", "analyzing_input"), "activity.started");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com", "see www.example.com", "HTTP://X", "ftp+x://y",
            "\u212A://x", "```code```", "~~~", "Traceback (most recent call last)", "줄\n바꿈", "탭\u0085끝"})
    @DisplayName("message는 URL·code fence·traceback·제어 문자를 거부한다")
    void unsafeMessagesRejected(String message) {
        expectViolation(updated("solution_generation").put("message", message), "activity.updated");
    }

    @Test
    @DisplayName("정수는 JSON 정수만(bool·실수·문자열 거부), 측정 진행량은 total을 넘을 수 없다")
    void strictIntegersAndMeasuredProgress() {
        expectViolation(withProgress(updated("verification"), RAW.getNodeFactory().numberNode(1.0), 3),
                "activity.updated");
        expectViolation(withProgress(updated("verification"), RAW.getNodeFactory().booleanNode(true), 3),
                "activity.updated");
        expectViolation(withProgress(updated("verification"), RAW.getNodeFactory().textNode("1"), 3),
                "activity.updated");
        expectViolation(withProgress(updated("verification"), RAW.getNodeFactory().numberNode(4), 3),
                "activity.updated");
        expectViolation(withProgress(updated("verification"), RAW.getNodeFactory().numberNode(0), 0),
                "activity.updated");
        assertThat(convert(withProgress(updated("verification"), RAW.getNodeFactory().numberNode(0), 1),
                "activity.updated").get("progress").toString()).isEqualTo("{\"checked\":0,\"total\":1}");

        expectViolation(updated("asset_understanding").put("processed_pages", 3).put("total_pages", 2),
                "activity.updated");
        expectViolation(completed("code_execution").put("method_label", "Python").put("duration_ms", -1),
                "activity.completed");
    }

    @Test
    @DisplayName("선택 필드의 null은 부재와 같고 출력에서 빠진다. 필수 필드·pages의 null은 거부한다")
    void nullHandling() {
        ObjectNode payload = updated("input_analysis");
        payload.putNull("status");
        payload.putNull("message");
        assertThat(convert(payload, "activity.updated").toString())
                .isEqualTo("{\"activityId\":\"activity-1\",\"kind\":\"input_analysis\"}");

        ObjectNode nullLabel = started("input_analysis");
        nullLabel.putNull("label");
        expectViolation(nullLabel, "activity.started");

        ObjectNode nullPages = completed("note_retrieval").put("match_status", "matched");
        nullPages.putNull("pages");
        expectViolation(nullPages, "activity.completed");

        // pages 기본값은 빈 배열이다(Brain은 exclude_none으로 dump해도 빈 tuple을 남긴다)
        assertThat(convert(completed("note_retrieval").put("match_status", "matched"), "activity.completed")
                .get("pages")).isEmpty();
    }

    @Test
    @DisplayName("asset_understanding targets: 1..50개, 중복·뒤집힌 범위·같은 asset 중첩 거부, 다른 asset은 겹쳐도 된다")
    void understandingTargets() {
        Consumer<ArrayNode> none = targets -> {
        };
        expectViolation(understanding(none), "activity.started");
        expectViolation(understanding(t -> {
            for (int i = 1; i <= SourceActivities.MAX_TARGETS + 1; i++) {
                target(t, "asset-" + i, 1, 1);
            }
        }), "activity.started");
        expectViolation(understanding(t -> {
            target(t, "asset-1", 1, 2);
            target(t, "asset-1", 1, 2);
        }), "activity.started");
        expectViolation(understanding(t -> {
            target(t, "asset-1", 1, 3);
            target(t, "asset-1", 3, 4);
        }), "activity.started");
        expectViolation(understanding(t -> target(t, "asset 1", 1, 1)), "activity.started");
        assertThat(convert(understanding(t -> {
            target(t, "asset-1", 1, 3);
            target(t, "asset-1", 4, 4);
            target(t, "asset-2", 1, 3);
        }), "activity.started").get("targets")).hasSize(3);
    }

    @Test
    @DisplayName("method_label은 code_execution·verification의 모든 phase에 필수이고 다른 kind에는 알 수 없는 필드다")
    void methodLabelPerKind() {
        expectViolation(canceled("verification"), "activity.canceled");
        expectViolation(failed("code_execution").put("code", "timeout"), "activity.failed");
        expectViolation(canceled("input_analysis").put("method_label", "x"), "activity.canceled");
        assertThat(convert(failed("code_execution").put("code", "timeout").put("method_label", "Python"),
                "activity.failed").get("methodLabel").asText()).isEqualTo("Python");
        expectViolation(failed("input_analysis").put("code", "segfault"), "activity.failed");
    }

    @Test
    @DisplayName("note_retrieval 완료 page 목록: 200개 상한·중복 거부")
    void retrievalPages() {
        ObjectNode duplicate = completed("note_retrieval").put("match_status", "matched");
        duplicate.putArray("pages").add(page("asset-1", 1)).add(page("asset-1", 1));
        expectViolation(duplicate, "activity.completed");

        ObjectNode tooMany = completed("note_retrieval").put("match_status", "matched");
        ArrayNode pages = tooMany.putArray("pages");
        for (int i = 1; i <= SourceActivities.MAX_PAGES + 1; i++) {
            pages.add(page("asset-1", i));
        }
        expectViolation(tooMany, "activity.completed");
    }

    @Test
    @DisplayName("오류 message에는 알 수 없는 key 이름·입력 값이 없다")
    void violationsDoNotEchoInput() {
        ObjectNode payload = updated("solution_generation").put("candidate_answer_DO-NOT-LEAK", "x = 42");
        Throwable error = catchThrowable(() -> convert(payload, "activity.updated"));
        assertThat(error).isInstanceOf(ActivityContractViolation.class);
        assertThat(error.getMessage()).isEqualTo("payload has an unknown field");

        Throwable label = catchThrowable(() -> convert(started("input_analysis").put("label", "DO-NOT-LEAK node"),
                "activity.started"));
        assertThat(label.getMessage()).doesNotContain(LEAK_SENTINEL);
    }

    // ------------------------------------------------------------------

    private static ObjectNode convert(ObjectNode payload, String type) {
        return convert(source(envelope(UUID.randomUUID().toString(), type, payload).toString()));
    }

    private static void expectViolation(ObjectNode payload, String type) {
        SourceEvent event = source(envelope(UUID.randomUUID().toString(), type, payload).toString());
        assertThatThrownBy(() -> convert(event)).as(payload.toString()).isInstanceOf(ActivityContractViolation.class);
    }

    static ObjectNode envelope(String runId, String type, ObjectNode payload) {
        ObjectNode envelope = JsonNodeFactory.instance.objectNode();
        envelope.put("contract_version", "brain-source-v1");
        envelope.put("run_id", runId);
        envelope.put("attempt", 1);
        envelope.put("fence_token", "fence-1");
        // activity_id 자체를 검사하는 경우가 있어 source_event_id는 activity_id에서 만들지 않는다
        envelope.put("source_event_id", runId + ":r0:" + type.substring(type.indexOf('.') + 1));
        envelope.put("occurred_at", "2026-10-17T09:00:01Z");
        envelope.put("type", type);
        envelope.set("payload", payload);
        return envelope;
    }

    private static ObjectNode base(String kind) {
        return JsonNodeFactory.instance.objectNode().put("kind", kind).put("activity_id", "activity-1");
    }

    private static ObjectNode started(String kind) {
        return base(kind);
    }

    private static ObjectNode updated(String kind) {
        return base(kind);
    }

    private static ObjectNode completed(String kind) {
        return base(kind);
    }

    private static ObjectNode failed(String kind) {
        return base(kind);
    }

    private static ObjectNode canceled(String kind) {
        return base(kind);
    }

    private static ObjectNode withProgress(ObjectNode payload, JsonNode checked, int total) {
        payload.put("method_label", "SymPy");
        payload.putObject("progress").put("total", total).set("checked", checked);
        return payload;
    }

    private static ObjectNode understanding(Consumer<ArrayNode> targets) {
        ObjectNode payload = started("asset_understanding").put("label", "reading_pages").put("reason", "viewer_focus");
        targets.accept(payload.putArray("targets"));
        return payload;
    }

    private static void target(ArrayNode targets, String assetId, int start, int end) {
        targets.addObject().put("asset_id", assetId).put("page_start", start).put("page_end", end);
    }

    private static ObjectNode page(String assetId, int page) {
        return JsonNodeFactory.instance.objectNode().put("asset_id", assetId).put("page", page);
    }

    /** camelCase key를 snake_case로 되돌린다(재귀). */
    static JsonNode snake(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                out.set(entry.getKey().replaceAll("([A-Z])", "_$1").toLowerCase(), snake(entry.getValue()));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            List<JsonNode> items = new ArrayList<>();
            node.forEach(items::add);
            items.forEach(item -> out.add(snake(item)));
            return out;
        }
        return node;
    }
}
