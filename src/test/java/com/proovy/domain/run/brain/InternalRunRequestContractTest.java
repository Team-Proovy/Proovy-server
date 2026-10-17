package com.proovy.domain.run.brain;

import static com.proovy.domain.run.brain.InternalRunFixtures.RAW;
import static com.proovy.domain.run.brain.InternalRunFixtures.entries;
import static com.proovy.domain.run.brain.InternalRunFixtures.fullRequest;
import static com.proovy.domain.run.brain.InternalRunFixtures.load;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.Iterator;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** requests·invalid·fingerprint fixture를 Java DTO·검증·fingerprint로 재현한다. */
class InternalRunRequestContractTest {

    @Test
    @DisplayName("정상 요청은 snake_case DTO로 읽히고 다시 쓰면 fixture와 같은 JSON이다")
    void validRequestsRoundTrip() throws Exception {
        for (Map.Entry<String, JsonNode> entry : entries("requests")) {
            InternalRunRequest request = InternalRunWire.read(entry.getValue(), InternalRunRequest.class);
            JsonNode written = RAW.readTree(InternalRunWire.write(request));
            assertThat(written).as(entry.getKey()).isEqualTo(entry.getValue());
            assertNoCamelCaseKeys(written);
        }
    }

    @Test
    @DisplayName("거부 요청 23종은 모두 계약 위반이고 오류에 grant·URL·입력 값이 없다")
    void invalidRequestsRejected() {
        var invalid = entries("invalid");
        assertThat(invalid).hasSize(23);
        for (Map.Entry<String, JsonNode> entry : invalid) {
            JsonNode request = entry.getValue().get("request");
            assertThatThrownBy(() -> InternalRunWire.read(request, InternalRunRequest.class))
                    .as(entry.getKey() + ": " + entry.getValue().get("rationale").asText())
                    .isInstanceOf(InternalRunContractViolation.class)
                    .hasNoCause()
                    .satisfies(error -> assertThat(error.getMessage())
                            .doesNotContain("grant-opaque-token", "sig=", "https://", "eyJ"));
        }
    }

    @Test
    @DisplayName("금지 필드(user_jwt·credit·metadata·authToken)는 표현할 곳이 없어 거부된다")
    void forbiddenFields() {
        for (String field : new String[]{"user_jwt", "authToken", "auth_token", "credit_balance", "metadata"}) {
            ObjectNode request = (ObjectNode) load("requests/full.json").deepCopy();
            request.put(field, "x");
            assertThatThrownBy(() -> InternalRunWire.read(request, InternalRunRequest.class))
                    .as(field)
                    .isInstanceOf(InternalRunContractViolation.class);
        }
        JsonNode written = InternalRunWire.tree(fullRequest());
        assertThat(written.findValues("user_jwt")).isEmpty();
        assertThat(written.findValues("auth_token")).isEmpty();
    }

    @Test
    @DisplayName("타입 강제 변환이 없다: 문자열 attempt·실수 attempt·숫자 text·offset 없는 deadline")
    void noCoercion() {
        assertRejected(request -> request.put("attempt", "1"));
        assertRejected(request -> request.put("attempt", 1.0));
        assertRejected(request -> ((ObjectNode) request.get("input")).put("text", 42));
        assertRejected(request -> ((ObjectNode) request.get("execution_options"))
                .put("request_deadline_at", "2026-10-02T09:30:00"));
        assertRejected(request -> request.remove("history"));
        assertRejected(request -> ((ObjectNode) request.get("execution_options").get("limits"))
                .remove("max_claims"));
        assertRejected(request -> request.putNull("history"));
        assertRejected(request -> request.putNull("turn_attachments"));
        assertRejected(request -> ((ObjectNode) request.get("input")).putNull("requested_tasks"));
        assertRejected(request -> ((ObjectNode) request.get("verified_solution_refs").get(0)).putNull("claims"));
    }

    @Test
    @DisplayName("fingerprint 27쌍: canonical·sha256이 Python과 같고 same/different 관계가 base와 맞는다")
    void fingerprintFixtures() {
        var cases = entries("fingerprint");
        assertThat(cases).hasSize(27);
        InternalRunFingerprint base = InternalRunWire.read(load("fingerprint/base.json").get("request"),
                InternalRunRequest.class).fingerprint();
        for (Map.Entry<String, JsonNode> entry : cases) {
            JsonNode example = entry.getValue();
            InternalRunFingerprint fingerprint = InternalRunWire.read(example.get("request"),
                    InternalRunRequest.class).fingerprint();
            assertThat(fingerprint.canonical()).as(entry.getKey())
                    .isEqualTo(example.get("expected_canonical").asText());
            assertThat(fingerprint.sha256()).as(entry.getKey())
                    .isEqualTo(example.get("expected_fingerprint").asText());
            if ("same".equals(example.get("relation").asText())) {
                assertThat(fingerprint).as(entry.getKey()).isEqualTo(base);
            } else {
                assertThat(fingerprint.sha256()).as(entry.getKey()).isNotEqualTo(base.sha256());
            }
        }
    }

    @Test
    @DisplayName("재전달은 deadline을 고정하고 attempt는 늘며 usage는 줄지 않는다 — fingerprint는 같다")
    void retryKeepsDeadlineAndBudget() {
        InternalRunRequest first = fullRequest();
        ExecutionUsage used = new ExecutionUsage(3, 2, 1_000, 40_000, 1);
        InternalRunRequest retry = first.forRetry(2, "fence-c3d4", used);

        assertThat(retry.fingerprint()).isEqualTo(first.fingerprint());
        assertThat(retry.executionOptions().requestDeadlineAt())
                .isEqualTo(first.executionOptions().requestDeadlineAt());
        assertThat(retry.executionOptions().limits()).isEqualTo(first.executionOptions().limits());
        assertThat(retry.requestId()).isEqualTo(first.requestId());
        assertThat(retry.runId()).isEqualTo(first.runId());

        assertThatThrownBy(() -> retry.forRetry(2, "fence-e5f6", used))
                .isInstanceOf(InternalRunContractViolation.class).hasMessageContaining("attempt");
        assertThatThrownBy(() -> retry.forRetry(3, "fence-e5f6", ExecutionUsage.ZERO))
                .isInstanceOf(InternalRunContractViolation.class).hasMessageContaining("usage");
        assertThatThrownBy(() -> retry.forRetry(3, "fence-e5f6", new ExecutionUsage(3, 2, 999, 40_000, 1)))
                .isInstanceOf(InternalRunContractViolation.class);
    }

    @Test
    @DisplayName("grant·read URL 재발급은 fingerprint를 바꾸지 않는다")
    void credentialRotationKeepsFingerprint() {
        InternalRunRequest first = fullRequest();
        InternalRunRequest rotated = first.withAssetAccess("grant-rotated", "https://storage.example/viewer?sig=new",
                Map.of("att-201", "https://storage.example/att?sig=new"));
        assertThat(rotated.fingerprint()).isEqualTo(first.fingerprint());
        assertThat(rotated.turnAttachments().getFirst().readUrl()).isEqualTo("https://storage.example/att?sig=new");
        assertThat(rotated.assetAccessGrant()).isEqualTo("grant-rotated");

        InternalRunRequest urlOnly = first.withAssetAccess(null, null,
                Map.of("att-201", "https://storage.example/att?sig=only"));
        assertThat(urlOnly.assetAccessGrant()).as("null은 유지").isEqualTo(first.assetAccessGrant());
        assertThat(urlOnly.viewerContext().readUrl()).isEqualTo(first.viewerContext().readUrl());
        assertThat(first.withAssetAccess(null, null, null)).isEqualTo(first);
    }

    @Test
    @DisplayName("문자열은 Python strip 집합으로 정규화되어 wire와 fingerprint가 같은 값을 쓴다")
    void stringsAreNormalized() {
        ObjectNode raw = (ObjectNode) load("requests/full.json").deepCopy();
        ((ObjectNode) raw.get("input")).put("text", " 첨부한 풀이를 검산해줘　");
        InternalRunRequest padded = InternalRunWire.read(raw, InternalRunRequest.class);
        assertThat(padded.input().text()).isEqualTo("첨부한 풀이를 검산해줘");
        assertThat(padded.fingerprint()).isEqualTo(fullRequest().fingerprint());
        assertThat(InternalRunWire.tree(padded).get("input").get("text").asText()).isEqualTo("첨부한 풀이를 검산해줘");
    }

    @Test
    @DisplayName("toString은 grant·read URL을 쓰지 않는다")
    void toStringRedactsSecrets() {
        InternalRunRequest request = fullRequest();
        String text = request + " " + request.viewerContext() + " " + request.turnAttachments();
        assertThat(text).doesNotContain("grant-opaque-token", "sig=abc", "sig=def", "storage.googleapis.com");
    }

    @Test
    @DisplayName("deadline은 offset을 보존해 ISO 문자열로 쓴다")
    void deadlineWireForm() {
        InternalRunRequest request = fullRequest();
        assertThat(request.executionOptions().requestDeadlineAt())
                .isEqualTo(OffsetDateTime.parse("2026-10-02T09:30:00Z"));
        assertThat(InternalRunWire.tree(request).get("execution_options").get("request_deadline_at").asText())
                .isEqualTo("2026-10-02T09:30:00Z");
    }

    @Test
    @DisplayName("deadline은 Brain과 같이 µs로 자른다")
    void deadlineTruncatedToMicros() {
        ObjectNode raw = (ObjectNode) load("requests/full.json").deepCopy();
        ((ObjectNode) raw.get("execution_options")).put("request_deadline_at", "2026-10-02T09:30:03.123456789Z");
        InternalRunRequest request = InternalRunWire.read(raw, InternalRunRequest.class);
        assertThat(request.executionOptions().requestDeadlineAt())
                .isEqualTo(OffsetDateTime.parse("2026-10-02T09:30:03.123456Z"));
    }

    @Test
    @DisplayName("internal MIME·role 값 집합이 1.75 브라우저 계약 enum과 같다")
    void enumsMatchBrowserContract() {
        assertThat(java.util.Arrays.stream(AssetMime.values()).map(AssetMime::wireValue))
                .containsExactlyInAnyOrderElementsOf(java.util.Arrays.stream(
                        com.proovy.domain.run.contract.TurnAttachmentMime.values())
                        .map(com.proovy.domain.run.contract.TurnAttachmentMime::wireValue).toList());
        assertThat(java.util.Arrays.stream(HistoryRole.values()).map(HistoryRole::wireValue))
                .containsExactlyInAnyOrderElementsOf(java.util.Arrays.stream(
                        com.proovy.domain.run.contract.MessageRole.values())
                        .map(com.proovy.domain.run.contract.MessageRole::wireValue).toList());
    }

    private static void assertRejected(java.util.function.Consumer<ObjectNode> mutation) {
        ObjectNode request = (ObjectNode) load("requests/full.json").deepCopy();
        mutation.accept(request);
        assertThatThrownBy(() -> InternalRunWire.read(request, InternalRunRequest.class))
                .isInstanceOf(InternalRunContractViolation.class);
    }

    private static void assertNoCamelCaseKeys(JsonNode node) {
        if (node.isObject()) {
            for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
                String name = names.next();
                assertThat(name).matches("[a-z0-9_]+");
                assertNoCamelCaseKeys(node.get(name));
            }
        } else if (node.isArray()) {
            node.forEach(InternalRunRequestContractTest::assertNoCamelCaseKeys);
        }
    }
}
