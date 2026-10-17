package com.proovy.domain.run.brain;

import static com.proovy.domain.run.brain.InternalRunFixtures.RAW;
import static com.proovy.domain.run.brain.InternalRunFixtures.entries;
import static com.proovy.domain.run.brain.InternalRunFixtures.load;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proovy.domain.run.entity.RunCancelCause;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** operations(status·cancel·cleanup·stream 거부)와 decisions 판정표를 Java DTO로 재현한다. */
class InternalRunOperationsContractTest {

    @Test
    @DisplayName("status·cancel·cleanup 요청·응답이 DTO로 읽히고 다시 쓰면 fixture와 같다")
    void operationsRoundTrip() throws Exception {
        int checked = 0;
        for (Map.Entry<String, JsonNode> entry : entries("operations")) {
            JsonNode example = entry.getValue();
            Class<?>[] types = switch (example.get("kind").asText()) {
                case "status" -> new Class<?>[]{RunStatusQuery.class, RunStatusResponse.class};
                case "cancel" -> new Class<?>[]{RunCancelRequest.class, RunCancelAck.class};
                case "cleanup" -> new Class<?>[]{RuntimeCleanupRequest.class, RuntimeCleanupResponse.class};
                case "stream" -> new Class<?>[]{InternalRunRequest.class, null};
                default -> throw new AssertionError(entry.getKey());
            };
            assertRoundTrip(entry.getKey(), example.get("request"), types[0]);
            if (types[1] != null) {
                assertRoundTrip(entry.getKey(), example.get("response"), types[1]);
            }
            checked++;
        }
        assertThat(checked).isEqualTo(12);
    }

    @Test
    @DisplayName("status 응답의 저장 terminal은 run.canceled를 허용하지만 stream union은 거부한다")
    void storedTerminalUnionDiffersFromStream() {
        JsonNode canceled = load("operations/status-terminal-canceled.json").get("response");
        RunStatusResponse status = InternalRunWire.read(canceled, RunStatusResponse.class);
        assertThat(status.state()).isEqualTo(RunRecordState.TERMINAL);
        assertThat(status.terminal().type()).isEqualTo(StoredTerminalType.RUN_CANCELED);
        assertThat(status.terminal().canceledCause()).isEqualTo(RunCancelCause.USER_REQUESTED);
        assertThatThrownBy(() -> InternalRunWire.read(canceled.get("terminal"), SourceEvent.class))
                .isInstanceOf(InternalRunContractViolation.class);

        JsonNode completed = load("operations/status-terminal.json").get("response");
        StoredTerminal terminal = InternalRunWire.read(completed, RunStatusResponse.class).terminal();
        assertThat(terminal.type()).isEqualTo(StoredTerminalType.RUN_COMPLETED);
        assertThat(terminal.canceledCause()).isNull();
        SourceEvent streamed = InternalRunWire.read(completed.get("terminal"), SourceEvent.class);
        assertThat(streamed.isTerminal()).isTrue();
        assertThat(streamed.payload()).isEqualTo(completed.get("terminal").get("payload"));
    }

    @Test
    @DisplayName("run.canceled payload는 알려진 cause 하나만 가진다")
    void canceledPayloadIsClosed() {
        ObjectNode response = (ObjectNode) load("operations/status-terminal-canceled.json").get("response").deepCopy();
        ((ObjectNode) response.get("terminal").get("payload")).put("cause", "provider_error");
        assertThatThrownBy(() -> InternalRunWire.read(response, RunStatusResponse.class))
                .isInstanceOf(InternalRunContractViolation.class);
        ((ObjectNode) response.get("terminal").get("payload")).put("cause", "user_requested").put("extra", 1);
        assertThatThrownBy(() -> InternalRunWire.read(response, RunStatusResponse.class))
                .isInstanceOf(InternalRunContractViolation.class);
    }

    @Test
    @DisplayName("status 응답 state 규칙: absent는 실행 사실이 없고 terminal state에만 terminal이 있다")
    void statusStateRules() {
        ObjectNode absent = (ObjectNode) load("operations/status-absent.json").get("response").deepCopy();
        absent.put("attempt", 1);
        assertThatThrownBy(() -> InternalRunWire.read(absent, RunStatusResponse.class))
                .isInstanceOf(InternalRunContractViolation.class);
        ObjectNode executing = (ObjectNode) load("operations/status-executing.json").get("response").deepCopy();
        executing.set("terminal", load("operations/status-terminal.json").get("response").get("terminal"));
        assertThatThrownBy(() -> InternalRunWire.read(executing, RunStatusResponse.class))
                .isInstanceOf(InternalRunContractViolation.class);
    }

    @Test
    @DisplayName("저장 terminal의 request_fingerprint는 원 요청의 Java fingerprint와 비교할 수 있다")
    void statusFingerprintMatchesRequest() {
        RunStatusResponse status = InternalRunWire.read(load("operations/status-terminal.json").get("response"),
                RunStatusResponse.class);
        InternalRunFingerprint fingerprint = InternalRunFixtures.fullRequest().fingerprint();
        assertThat(status.matches(fingerprint)).isTrue();
        assertThat(InternalRunWire.read(load("operations/status-absent.json").get("response"),
                RunStatusResponse.class).matches(fingerprint)).isFalse();
    }

    @Test
    @DisplayName("decisions.json 4×4×2 판정표와 binding override를 같은 값으로 판정한다")
    void decisionTable() {
        JsonNode table = load("decisions.json");
        assertThat(table.get("version").asText()).isEqualTo("run-decisions-v2");
        int rows = 0;
        for (JsonNode row : table.get("decisions")) {
            RunOperation operation = wire(RunOperation.values(), row.get("operation").asText());
            RunRecordState state = wire(RunRecordState.values(), row.get("record_state").asText());
            RunDecision expected = wire(RunDecision.values(), row.get("decision").asText());
            assertThat(RunDecisions.decide(operation, state, row.get("fingerprint_match").asBoolean()))
                    .as(row.toString()).isEqualTo(expected);
            rows++;
        }
        assertThat(rows).isEqualTo(32);
        JsonNode override = table.get("binding_overrides").get(0);
        assertThat(override.get("reason").asText()).isEqualTo(RunDecisions.REQUEST_ID_BOUND_TO_OTHER_RUN);
        for (RunRecordState state : RunRecordState.values()) {
            assertThat(RunDecisions.decideStream(true, state, true)).isEqualTo(RunDecision.REJECT);
        }
        assertThat(RunDecisions.decideStream(false, RunRecordState.EXECUTING, true)).isEqualTo(RunDecision.OBSERVE);
    }

    private static void assertRoundTrip(String name, JsonNode fixture, Class<?> type) throws Exception {
        Object message = InternalRunWire.read(fixture, type);
        assertThat(RAW.readTree(InternalRunWire.write(message))).as(name + " " + type.getSimpleName())
                .isEqualTo(fixture);
    }

    private static <E extends Enum<E> & com.proovy.domain.run.entity.WireValue> E wire(E[] values, String value) {
        for (E candidate : values) {
            if (candidate.wireValue().equals(value)) {
                return candidate;
            }
        }
        throw new AssertionError("unknown wire value " + value);
    }
}
