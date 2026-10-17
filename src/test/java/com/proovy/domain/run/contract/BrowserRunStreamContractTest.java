package com.proovy.domain.run.contract;

import static com.proovy.domain.run.contract.BrowserRunContractTestSupport.MAPPER;
import static com.proovy.domain.run.contract.BrowserRunContractTestSupport.entries;
import static com.proovy.domain.run.contract.BrowserRunContractTestSupport.load;
import static com.proovy.domain.run.contract.BrowserRunContractTestSupport.schemaOk;
import static com.proovy.domain.run.contract.BrowserRunContractTestSupport.text;
import static com.proovy.domain.run.contract.BrowserRunContractTestSupport.toTree;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** streams/·heartbeat fixture를 Java DTO와 stream 규칙 S1~S7 검사기로 재현한다. */
class BrowserRunStreamContractTest {

    private static final Set<String> TERMINAL_TYPES = Set.of("run.completed", "run.failed", "run.canceled");
    private static final Set<String> AFTER_CANCELING = Set.of(
            "activity.completed", "activity.failed", "activity.canceled", "run.canceled");

    @Test
    @DisplayName("정상 stream은 S1~S7을 모두 지키고 frame·Server 소유 payload가 DTO로 왕복된다")
    void validStreams() throws Exception {
        List<Map.Entry<String, JsonNode>> cases = entries("streams/valid");
        Set<String> finals = new HashSet<>();
        for (Map.Entry<String, JsonNode> entry : cases) {
            String name = entry.getKey();
            JsonNode frames = entry.getValue().get("frames");
            assertThat(violations(frames)).as(name).isEmpty();
            finals.add(entry.getValue().get("finalStatus").asText());
            for (JsonNode frame : frames) {
                BrowserRunFrame dto = MAPPER.treeToValue(frame, BrowserRunFrame.class);
                assertThat(toTree(dto)).as(name + " seq " + dto.seq()).isEqualTo(frame);
                assertThat(dto.contractVersion()).isEqualTo(BrowserRunContract.FRAME_CONTRACT_VERSION);
                Optional<ServerFrameType> serverType = ServerFrameType.of(dto.type());
                if (serverType.isPresent()) {
                    Object payload = MAPPER.treeToValue(dto.payload(), serverType.get().payloadType());
                    assertThat(toTree(payload)).as(name + " " + dto.type()).isEqualTo(dto.payload());
                }
            }
            assertSseMatchesFrames(name, entry.getValue().get("sse").asText(), frames);
        }
        assertThat(finals).containsExactlyInAnyOrder("COMPLETED", "FAILED", "CANCELED");
    }

    @Test
    @DisplayName("거부 stream은 적힌 규칙을 위반하고 schema 거부 여부도 fixture와 같다")
    void invalidStreams() {
        Set<String> rules = new HashSet<>();
        for (Map.Entry<String, JsonNode> entry : entries("streams/invalid")) {
            JsonNode fixture = entry.getValue();
            String rule = fixture.get("violates").asText();
            rules.add(rule);
            assertThat(violations(fixture.get("frames"))).as(entry.getKey()).contains(rule);
            boolean schemaFails = false;
            for (JsonNode frame : fixture.get("frames")) {
                schemaFails |= !schemaOk("BrowserFrame", frame);
            }
            assertThat(schemaFails).as(entry.getKey()).isEqualTo(fixture.get("schema_rejects").asBoolean());
        }
        assertThat(rules).containsExactlyInAnyOrder("S1", "S2", "S3", "S4", "S5", "S6", "S7");
    }

    @Test
    @DisplayName("Server 소유 payload factory가 cause별 고정 message를 쓴다")
    void canceledPayloadFactory() {
        JsonNode frames = load("streams/valid/canceled-asset-deleting.json").get("frames");
        JsonNode last = frames.get(frames.size() - 1).get("payload");
        RunCanceledPayload payload = RunCanceledPayload.of(
                last.get("assistantMessageId").asText(), CancelCause.ASSET_DELETING);
        assertThat(toTree(payload)).isEqualTo(last);
    }

    @Test
    @DisplayName("replay와 live의 차이는 read-time sourceReferences[].available뿐이다")
    void replayDiffersOnlyInAvailability() {
        JsonNode fixture = load("streams/valid/replay-source-unavailable.json");
        JsonNode replay = fixture.get("frames").deepCopy();
        JsonNode references = replay.get(replay.size() - 1).at("/payload/assistantResponse/sourceReferences");
        assertThat(references.size()).isPositive();
        for (JsonNode reference : references) {
            assertThat(reference.get("available").asBoolean()).isFalse();
            ((ObjectNode) reference).put("available", true);
        }
        assertThat(replay).isEqualTo(fixture.get("live"));
    }

    @Test
    @DisplayName("heartbeat는 15초 주기의 comment이고 id·data·event가 없다")
    void heartbeat() {
        assertThat(text("heartbeat.txt")).isEqualTo(BrowserRunContract.HEARTBEAT_COMMENT);
        JsonNode invalid = load("heartbeat-invalid.json");
        assertThat(invalid.get("valid").asText()).isEqualTo(BrowserRunContract.HEARTBEAT_COMMENT);
        Pattern frameField = Pattern.compile("^(id|data|event):", Pattern.MULTILINE);
        assertThat(frameField.matcher(BrowserRunContract.HEARTBEAT_COMMENT).find()).isFalse();
        for (JsonNode testCase : invalid.get("cases")) {
            assertThat(frameField.matcher(testCase.get("sse").asText()).find()).as(testCase.get("name").asText())
                    .isTrue();
        }
    }

    private static void assertSseMatchesFrames(String name, String sse, JsonNode frames) throws Exception {
        List<String> blocks = new ArrayList<>();
        for (String block : sse.split("\n\n")) {
            if (!block.isEmpty()) {
                blocks.add(block);
            }
        }
        assertThat(blocks).as(name).hasSize(frames.size());
        for (int i = 0; i < blocks.size(); i++) {
            Map<String, String> fields = new HashMap<>();
            for (String line : blocks.get(i).split("\n")) {
                String[] parts = line.split(": ", 2);
                fields.put(parts[0], parts[1]);
            }
            JsonNode frame = frames.get(i);
            assertThat(fields).as(name).containsOnlyKeys("event", "id", "data");
            assertThat(fields.get("event")).isEqualTo(frame.get("type").asText());
            assertThat(fields.get("id"))
                    .isEqualTo(new RunEventCursor(frame.get("runId").asText(), frame.get("seq").asLong()).toString());
            assertThat(MAPPER.readTree(fields.get("data"))).isEqualTo(frame);
        }
    }

    /** manifest stream_rules S1~S7 검사기. */
    static Set<String> violations(JsonNode frames) {
        Set<String> found = new HashSet<>();
        int size = frames.size();
        if (size == 0 || !type(frames.get(0)).equals("run.created")) {
            found.add("S1");
        }
        for (int i = 0; i < size; i++) {
            JsonNode seq = frames.get(i).get("seq");
            if (seq == null || seq.asLong() != i + 1) {
                found.add("S1");
            }
        }
        List<Integer> terminals = new ArrayList<>();
        List<Integer> cancelings = new ArrayList<>();
        List<Integer> pagePositions = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            String type = type(frames.get(i));
            if (TERMINAL_TYPES.contains(type)) {
                terminals.add(i);
            }
            if (type.equals("run.canceling")) {
                cancelings.add(i);
            }
            if (type.startsWith("response.page.")) {
                pagePositions.add(i);
            }
        }
        if (terminals.size() != 1 || terminals.get(0) != size - 1) {
            found.add("S2");
        }
        JsonNode last = size == 0 ? null : frames.get(size - 1);
        if (!cancelings.isEmpty()) {
            if (cancelings.size() > 1 || !type(last).equals("run.canceled")) {
                found.add("S3");
            }
            for (int i = cancelings.get(0) + 1; i < size; i++) {
                if (!AFTER_CANCELING.contains(type(frames.get(i)))) {
                    found.add("S3");
                }
            }
        }
        if (!pagePositions.isEmpty()) {
            if (!type(last).equals("run.completed") || !pagesMatchCompleted(frames, pagePositions.size())) {
                found.add("S4");
            }
        } else if (last != null && type(last).equals("run.completed")) {
            found.add("S4");
        }
        Set<String> runIds = new HashSet<>();
        for (JsonNode frame : frames) {
            runIds.add(frame.path("runId").asText(null));
        }
        if (runIds.size() != 1) {
            found.add("S5");
        }
        if (size > 0 && type(frames.get(0)).equals("run.created")) {
            String messageId = frames.get(0).at("/payload/assistantMessageId").asText(null);
            for (int i = 1; i < size; i++) {
                JsonNode payloadId = frames.get(i).at("/payload/assistantMessageId");
                if (!payloadId.isMissingNode() && !payloadId.asText().equals(messageId)) {
                    found.add("S5");
                }
            }
        }
        if (!activitiesAreOrdered(frames)) {
            found.add("S6");
        }
        for (JsonNode frame : frames) {
            if (!schemaOk("BrowserFrame", frame)) {
                found.add("S7");
            }
        }
        return found;
    }

    private static boolean pagesMatchCompleted(JsonNode frames, int pageFrameCount) {
        int size = frames.size();
        JsonNode pages = frames.get(size - 1).at("/payload/assistantResponse/responsePages");
        int blockSize = pages.size() * 2;
        if (pageFrameCount != blockSize || size - 1 - blockSize < 0) {
            return false;
        }
        for (int index = 0; index < pages.size(); index++) {
            JsonNode started = frames.get(size - 1 - blockSize + 2 * index);
            JsonNode completed = frames.get(size - blockSize + 2 * index);
            JsonNode page = pages.get(index);
            if (!type(started).equals("response.page.started") || !type(completed).equals("response.page.completed")) {
                return false;
            }
            JsonNode startedPayload = started.get("payload");
            JsonNode completedPayload = completed.get("payload");
            String pageId = page.get("responsePageId").asText();
            if (startedPayload.get("pageIndex").asInt() != index
                    || completedPayload.get("pageIndex").asInt() != index
                    || !startedPayload.get("responsePageId").asText().equals(pageId)
                    || !completedPayload.get("responsePageId").asText().equals(pageId)
                    || !startedPayload.get("presentation").equals(page.get("presentation"))
                    || !Objects.equals(nullableText(startedPayload.get("title")), nullableText(page.get("title")))) {
                return false;
            }
        }
        return true;
    }

    private static boolean activitiesAreOrdered(JsonNode frames) {
        Map<String, String> openKinds = new HashMap<>();
        Set<String> closed = new HashSet<>();
        for (JsonNode frame : frames) {
            String type = type(frame);
            if (!type.startsWith("activity.")) {
                continue;
            }
            String id = frame.at("/payload/activityId").asText();
            String kind = frame.at("/payload/kind").asText();
            if (type.equals("activity.started")) {
                if (openKinds.containsKey(id) || closed.contains(id)) {
                    return false;
                }
                openKinds.put(id, kind);
                continue;
            }
            if (!kind.equals(openKinds.get(id))) {
                return false;
            }
            if (!type.equals("activity.updated")) {
                openKinds.remove(id);
                closed.add(id);
            }
        }
        return true;
    }

    private static String type(JsonNode frame) {
        return frame.path("type").asText("");
    }

    private static String nullableText(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }
}
