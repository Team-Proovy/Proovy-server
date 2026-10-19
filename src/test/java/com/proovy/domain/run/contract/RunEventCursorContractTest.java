package com.proovy.domain.run.contract;

import static com.proovy.domain.run.contract.BrowserRunContractTestSupport.load;
import static com.proovy.domain.run.contract.BrowserRunContractTestSupport.schemaOk;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** cursors/cases.json을 {@link RunEventCursor#resolve}로 재현한다. */
class RunEventCursorContractTest {

    private static final JsonNode CURSORS = load("cursors/cases.json");

    @Test
    @DisplayName("cursor 판정·replay 시작점·live 합류 여부가 fixture와 같다")
    void cursorCases() {
        JsonNode cases = CURSORS.get("cases");
        assertThat(cases.size()).isGreaterThanOrEqualTo(20);
        for (JsonNode testCase : cases) {
            String name = testCase.get("name").asText();
            JsonNode run = CURSORS.at("/runs/" + testCase.get("run").asText());
            String header = textOrNull(testCase.get("lastEventIdHeader"));
            String query = textOrNull(testCase.get("lastEventIdQuery"));
            RunEventCursor.ReplayStart start = RunEventCursor.resolve(
                    run.get("runId").asText(), run.get("lastDurableSeq").asLong(), header, query);
            JsonNode expected = testCase.get("expected");
            if (expected.has("code")) {
                assertThat(start.error()).as(name).isNotNull();
                assertThat(start.error().getCode()).as(name).isEqualTo(expected.get("code").asText());
                assertThat(start.error().getHttpStatus().value()).isEqualTo(expected.get("http").asInt());
            } else {
                assertThat(start.isRejected()).as(name).isFalse();
                assertThat(start.fromSeq()).as(name).isEqualTo(expected.get("replayFromSeq").asLong());
                boolean live = !PublicRunStatus.valueOf(run.get("status").asText()).isTerminal();
                assertThat(live).as(name).isEqualTo(expected.get("thenLive").asBoolean());
                if (expected.has("frameCount")) {
                    assertThat(start.fromSeq() - 1).as(name).isEqualTo(run.get("lastDurableSeq").asLong());
                }
            }
            List<String> raws = new ArrayList<>();
            if (header != null) {
                raws.add(header);
            }
            if (query != null) {
                raws.add(query);
            }
            for (String raw : raws) {
                boolean schemaRejects = testCase.get("schemaRejects").asBoolean();
                assertThat(schemaOk("EventId", TextNode.valueOf(raw))).as(name).isEqualTo(!schemaRejects);
                assertThat(RunEventCursor.parse(raw).isPresent()).as(name).isEqualTo(!schemaRejects);
            }
        }
    }

    @Test
    @DisplayName("run 접근 거부는 cursor보다 먼저인 404 RUN4041이다")
    void accessCases() {
        for (JsonNode testCase : CURSORS.get("accessCases")) {
            assertThat(testCase.at("/expected/code").asText()).isEqualTo("RUN4041");
            assertThat(testCase.at("/expected/http").asInt()).isEqualTo(404);
        }
        assertThat(CURSORS.get("precedence").get(1).asText()).startsWith("run access");
    }

    @Test
    @DisplayName("cursor 문자열은 SSE id 형식 {runId}:{seq}로 되돌아간다")
    void cursorRoundTrip() {
        String raw = CURSORS.at("/runs/terminal/runId").asText() + ":14";
        assertThat(RunEventCursor.parse(raw)).hasValueSatisfying(cursor -> {
            assertThat(cursor.seq()).isEqualTo(14);
            assertThat(cursor.toString()).isEqualTo(raw);
        });
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }
}
