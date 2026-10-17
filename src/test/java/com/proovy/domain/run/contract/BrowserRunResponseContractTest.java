package com.proovy.domain.run.contract;

import static com.proovy.domain.run.contract.BrowserRunContractTestSupport.MAPPER;
import static com.proovy.domain.run.contract.BrowserRunContractTestSupport.entries;
import static com.proovy.domain.run.contract.BrowserRunContractTestSupport.schemaErrors;
import static com.proovy.domain.run.contract.BrowserRunContractTestSupport.schemaOk;
import static com.proovy.domain.run.contract.BrowserRunContractTestSupport.toTree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.proovy.domain.run.entity.AiRunStatus;
import com.proovy.domain.run.entity.ClarificationReason;
import com.proovy.domain.run.entity.ResponseDisposition;
import com.proovy.domain.run.entity.RunCancelCause;
import com.proovy.domain.run.entity.RunFailureCode;
import com.proovy.domain.run.entity.WireValue;
import com.proovy.global.response.ApiResponse;
import com.proovy.global.response.ErrorCode;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** responses/ fixture를 Java DTO·ApiResponse·ErrorCode로 재현한다. */
class BrowserRunResponseContractTest {

    private static final Map<String, Class<?>> RESULT_TYPES = Map.of(
            "RunStatusResponse", RunStatus.class,
            "RunCancelResponse", RunCancelResult.class,
            "ConversationPageResponse", ConversationPage.class);

    @Test
    @DisplayName("정상 성공 응답은 DTO로 읽고 ApiResponse로 다시 쓰면 같은 JSON이며 schema를 통과한다")
    void successResponsesRoundTrip() throws Exception {
        int checked = 0;
        for (Map.Entry<String, JsonNode> entry : entries("responses/valid")) {
            JsonNode fixture = entry.getValue();
            Class<?> type = RESULT_TYPES.get(fixture.get("schema").asText());
            if (type == null) {
                continue;
            }
            Object result = MAPPER.treeToValue(fixture.at("/body/result"), type);
            JsonNode written = toTree(ApiResponse.success(result));
            assertThat(written).as(entry.getKey()).isEqualTo(fixture.get("body"));
            assertThat(schemaErrors(fixture.get("schema").asText(), written)).as(entry.getKey()).isEmpty();
            checked++;
        }
        assertThat(checked).isEqualTo(15);
    }

    @Test
    @DisplayName("오류 응답은 ErrorCode의 code·HTTP·고정 message와 같고 RUN4091만 activeRunId를 담는다")
    void errorResponsesMatchErrorCode() {
        Set<String> codes = new HashSet<>();
        for (Map.Entry<String, JsonNode> entry : entries("responses/valid")) {
            JsonNode fixture = entry.getValue();
            if (!fixture.get("schema").asText().equals("ErrorResponse")) {
                continue;
            }
            JsonNode body = fixture.get("body");
            ErrorCode code = ErrorCode.valueOf(body.get("code").asText());
            assertThat(code.getHttpStatus().value()).as(entry.getKey()).isEqualTo(fixture.get("http").asInt());
            ApiResponse<?> response = code == ErrorCode.RUN4091
                    ? ApiResponse.failure(code, new ActiveRunConflict(body.at("/result/activeRunId").asText()))
                    : ApiResponse.failure(code);
            assertThat(toTree(response)).as(entry.getKey()).isEqualTo(body);
            codes.add(code.getCode());
        }
        assertThat(codes).containsExactlyInAnyOrder(
                "AUTH4010", "AUTH4012", "AUTH4013", "RUN4001", "RUN4002", "RUN4003", "RUN4004",
                "RUN4021", "RUN4041", "RUN4091", "RUN4092", "RUN4221", "RUN4291");
    }

    @Test
    @DisplayName("거부 응답은 schema가 거부하고, 내부 RETRYING 노출은 DTO도 읽지 않는다")
    void invalidResponsesAreRejected() {
        for (Map.Entry<String, JsonNode> entry : entries("responses/invalid")) {
            JsonNode fixture = entry.getValue();
            assertThat(schemaOk(fixture.get("schema").asText(), fixture.get("body"))).as(entry.getKey()).isFalse();
        }
        JsonNode retrying = BrowserRunContractTestSupport.load("responses/invalid/status-retrying.json");
        assertThatThrownBy(() -> MAPPER.treeToValue(retrying.at("/body/result"), RunStatus.class))
                .hasMessageContaining("RETRYING");
    }

    @Test
    @DisplayName("공개 상태는 내부 상태를 투영한다 — RETRYING은 RUNNING, cancelable은 PENDING·RUNNING")
    void publicStatusProjection() {
        for (AiRunStatus status : AiRunStatus.values()) {
            PublicRunStatus projected = PublicRunStatus.from(status);
            assertThat(projected.isTerminal()).as(status.name()).isEqualTo(status.isTerminal());
        }
        assertThat(PublicRunStatus.from(AiRunStatus.RETRYING)).isEqualTo(PublicRunStatus.RUNNING);
        for (Map.Entry<String, JsonNode> entry : entries("responses/valid")) {
            JsonNode result = entry.getValue().at("/body/result");
            if (entry.getValue().get("schema").asText().equals("RunStatusResponse")) {
                PublicRunStatus status = PublicRunStatus.valueOf(result.get("status").asText());
                assertThat(result.get("cancelable").asBoolean()).as(entry.getKey()).isEqualTo(status.isCancelable());
            }
        }
    }

    @Test
    @DisplayName("cancel cause는 1.74 RunCancelCause와 1:1이고 고정 message가 fixture와 같다")
    void cancelCauseMapping() {
        for (RunCancelCause cause : RunCancelCause.values()) {
            assertThat(CancelCause.from(cause).wireValue()).isEqualTo(cause.wireValue());
        }
        assertThat(toTree(AssistantCancel.of(CancelCause.USER_REQUESTED)))
                .isEqualTo(BrowserRunContractTestSupport.load(
                                "responses/valid/conversation-clarification-failed-canceled.json")
                        .at("/body/result/messages/5/assistant/cancel"));
    }

    @Test
    @DisplayName("응답 fixture의 terminal-v2 enum 값은 1.74 엔티티 enum의 wire 값 안에 있다")
    void terminalEnumValuesMatchEntities() {
        Set<String> dispositions = wireValues(ResponseDisposition.values());
        Set<String> reasons = wireValues(ClarificationReason.values());
        Set<String> failureCodes = wireValues(RunFailureCode.values());
        int seen = 0;
        for (Map.Entry<String, JsonNode> entry : entries("responses/valid")) {
            for (JsonNode node : entry.getValue().findValues("responseDisposition")) {
                if (!node.isNull()) {
                    assertThat(dispositions).as(entry.getKey()).contains(node.asText());
                    seen++;
                }
            }
            for (JsonNode node : entry.getValue().findValues("clarificationReason")) {
                if (!node.isNull()) {
                    assertThat(reasons).as(entry.getKey()).contains(node.asText());
                }
            }
            for (JsonNode failure : entry.getValue().findValues("failure")) {
                if (!failure.isNull()) {
                    assertThat(failureCodes).as(entry.getKey()).contains(failure.get("code").asText());
                }
            }
        }
        assertThat(seen).isPositive();
    }

    @Test
    @DisplayName("시각을 가진 계약 record는 나노초 시각을 마이크로초로 절단해 schema의 OccurredAt 형식을 지킨다")
    void wireTimeFitsSchema() {
        Instant nanos = Instant.parse("2026-10-17T09:00:00.123456789Z");
        assertThat(occurredAt(nanos).asText()).isEqualTo("2026-10-17T09:00:00.123456Z");
        assertThat(schemaOk("OccurredAt", occurredAt(nanos))).isTrue();
        RunStatus status = new RunStatus("r", "q", "1", "u", "a", PublicRunStatus.COMPLETED, false, 1,
                "substantive", null, nanos, nanos);
        assertThat(status.createdAt()).isEqualTo(BrowserRunContract.wireTime(nanos));
        assertThat(status.completedAt()).isEqualTo(BrowserRunContract.wireTime(nanos));
        ConversationMessage message = new ConversationMessage("m", MessageRole.ASSISTANT,
                ConversationMessageStatus.STREAMING, "r", nanos, null, AssistantMessageContent.placeholder());
        assertThat(toTree(message).get("createdAt").asText()).isEqualTo("2026-10-17T09:00:00.123456Z");
        assertThat(BrowserRunContract.wireTime(null)).isNull();
    }

    @Test
    @DisplayName("STREAMING placeholder는 fixture와 같은 빈 assistant 본문이다")
    void placeholderMatchesFixture() {
        JsonNode fixture = BrowserRunContractTestSupport.load("responses/valid/conversation-active-run.json")
                .at("/body/result/messages/3/assistant");
        assertThat(toTree(AssistantMessageContent.placeholder())).isEqualTo(fixture);
    }

    /** DTO가 시각을 mapper 설정과 무관하게 ISO 문자열로 쓰는지까지 함께 본다. */
    private static JsonNode occurredAt(Instant instant) {
        BrowserRunFrame frame = new BrowserRunFrame(
                BrowserRunContract.FRAME_CONTRACT_VERSION, "r", 1, instant, "run.created", null);
        return toTree(frame).get("occurredAt");
    }

    private static <E extends Enum<E> & WireValue> Set<String> wireValues(E[] values) {
        return Arrays.stream(values).map(WireValue::wireValue).collect(Collectors.toSet());
    }
}
