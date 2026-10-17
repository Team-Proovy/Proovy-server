package com.proovy.domain.run.contract;

import static com.proovy.domain.run.contract.BrowserRunContractTestSupport.load;
import static com.proovy.domain.run.contract.BrowserRunContractTestSupport.toTree;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.proovy.global.response.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * admission/decisions.json·cancel/cases.json 판정표를 Java 참조 판정으로 재현한다.
 * 실제 접수·취소 처리는 후속 태스크(1.79 등)가 이 판정 순서대로 구현한다.
 */
class BrowserRunDecisionTableContractTest {

    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "CANCELED");

    @Test
    @DisplayName("POST 접수 판정은 precedence 순서를 따르고 각 오류 code가 ErrorCode와 같다")
    void admissionDecisions() {
        JsonNode decisions = load("admission/decisions.json");
        assertThat(toTree(List.of(
                "authenticated", "request:schema_invalid", "request:unsupported",
                "existing:same_user_same_fingerprint", "existing:other", "note", "references",
                "activeRun", "rateLimited", "balance", "accept")))
                .isEqualTo(decisions.get("precedence"));
        for (JsonNode testCase : decisions.get("cases")) {
            String name = testCase.get("name").asText();
            assertThat(toTree(judgeAdmission(testCase.get("state")))).as(name).isEqualTo(testCase.get("expected"));
        }
    }

    @Test
    @DisplayName("cancel 판정은 body 검사 → run 접근 → 결과 순서이고 결과는 응답 fixture와 같다")
    void cancelCases() {
        JsonNode cancel = load("cancel/cases.json");
        for (JsonNode testCase : cancel.get("cases")) {
            String name = testCase.get("name").asText();
            JsonNode rawBody = testCase.get("rawBody");
            Map<String, Object> actual = judgeCancel(rawBody.isNull() ? null : rawBody.asText(),
                    testCase.get("run").asText());
            assertThat(toTree(actual)).as(name).isEqualTo(testCase.get("expected"));
            if (actual.containsKey("fixture")) {
                JsonNode response = load(actual.get("fixture") + ".json");
                assertThat(response.at("/body/result/status").asText()).as(name).isEqualTo(actual.get("status"));
            }
        }
    }

    private static Map<String, Object> judgeAdmission(JsonNode state) {
        if (!state.get("authenticated").asBoolean()) {
            return error(ErrorCode.AUTH4010);
        }
        String request = state.get("request").asText();
        if (request.equals("schema_invalid")) {
            return error(ErrorCode.RUN4001);
        }
        if (request.equals("unsupported")) {
            return error(ErrorCode.RUN4002);
        }
        JsonNode existing = state.get("existing");
        if (!existing.isNull()) {
            if (existing.get("sameUser").asBoolean() && existing.get("sameFingerprint").asBoolean()) {
                Map<String, Object> replay = new LinkedHashMap<>();
                replay.put("http", 200);
                replay.put("outcome", "stream_replay");
                replay.put("replayFromSeq", 1);
                replay.put("thenLive", !TERMINAL.contains(existing.get("runStatus").asText()));
                replay.put("sideEffects", "none");
                return replay;
            }
            return error(ErrorCode.RUN4092);
        }
        if (Set.of("missing", "other_user", "deleting").contains(state.get("note").asText())) {
            return error(ErrorCode.RUN4041);
        }
        if (!state.get("references").asText().equals("ok")) {
            return error(ErrorCode.RUN4221);
        }
        if (!state.get("activeRun").isNull()) {
            Map<String, Object> conflict = error(ErrorCode.RUN4091);
            conflict.put("result", new ActiveRunConflict(state.at("/activeRun/runId").asText()));
            return conflict;
        }
        if (state.get("rateLimited").asBoolean()) {
            Map<String, Object> limited = error(ErrorCode.RUN4291);
            limited.put("headers", Map.of("Retry-After", "30"));
            return limited;
        }
        if (!state.get("balance").asText().equals("sufficient")) {
            return error(ErrorCode.RUN4021);
        }
        return new LinkedHashMap<>(Map.of(
                "http", 200, "outcome", "stream_new", "replayFromSeq", 1, "sideEffects", "created"));
    }

    private static Map<String, Object> judgeCancel(String rawBody, String run) {
        if (rawBody != null) {
            return codeOnly(ErrorCode.RUN4001);
        }
        if (run.equals("missing") || run.equals("other_user")) {
            return codeOnly(ErrorCode.RUN4041);
        }
        String[] outcome = switch (run) {
            case "pending" -> new String[]{"accepted-pending", "CANCELED"};
            case "running" -> new String[]{"accepted-running", "CANCELING"};
            case "canceling" -> new String[]{"already-canceling", "CANCELING"};
            case "completed" -> new String[]{"already-terminal", "COMPLETED"};
            default -> throw new IllegalArgumentException(run);
        };
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("http", 200);
        result.put("fixture", "responses/valid/cancel-" + outcome[0]);
        result.put("status", outcome[1]);
        return result;
    }

    private static Map<String, Object> error(ErrorCode code) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("http", code.getHttpStatus().value());
        error.put("outcome", "error");
        error.put("code", code.getCode());
        error.put("sideEffects", "none");
        return error;
    }

    private static Map<String, Object> codeOnly(ErrorCode code) {
        return new LinkedHashMap<>(Map.of("http", code.getHttpStatus().value(), "code", code.getCode()));
    }
}
