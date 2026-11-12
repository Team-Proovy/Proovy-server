package com.proovy.domain.run.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * assistant 메시지 본문. assistantResponse·verificationSummary는 Brain terminal-v2 계약
 * (AssistantResponseV1·SolutionVerificationSummary)을 그대로 전달하므로 JSON 그대로 둔다.
 * PENDING/STREAMING placeholder도 이 객체를 두되 모든 값은 null, verificationSummary는 빈 배열이다
 * ({@link #placeholder()}) — Candidate 본문은 공개하지 않는다.
 * <p>
 * Phase 2 확장(browser-run-fixtures-v1.1 {@code CompletionExtension}, task 2.15): 완료 메시지가 확장 terminal로
 * 저장됐으면 {@code responseKind}·{@code assuranceScope}·{@code solutionChecks}가 <b>함께</b> 있고, 그 밖에는 셋 다 key가 없다
 * (확장 이전 응답·실패·취소·placeholder). 값은 SSE {@code run.completed}와 같은 함수로 만든다.
 */
public record AssistantMessageContent(
        String responseDisposition,
        String clarificationReason,
        JsonNode assistantResponse,
        List<JsonNode> verificationSummary,
        AssistantFailure failure,
        AssistantCancel cancel,
        @JsonInclude(JsonInclude.Include.NON_NULL) String responseKind,
        @JsonInclude(JsonInclude.Include.NON_NULL) String assuranceScope,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<JsonNode> solutionChecks
) {

    @JsonCreator
    public AssistantMessageContent {
        boolean any = responseKind != null || assuranceScope != null || solutionChecks != null;
        boolean all = responseKind != null && assuranceScope != null && solutionChecks != null;
        if (any && !all) {
            throw new IllegalArgumentException("completion extension fields appear together");
        }
        solutionChecks = solutionChecks == null ? null : List.copyOf(solutionChecks);
    }

    /** 확장 필드 없는 완료 본문. */
    public AssistantMessageContent(String responseDisposition, String clarificationReason, JsonNode assistantResponse,
                                   List<JsonNode> verificationSummary, AssistantFailure failure,
                                   AssistantCancel cancel) {
        this(responseDisposition, clarificationReason, assistantResponse, verificationSummary, failure, cancel,
                null, null, null);
    }

    public static AssistantMessageContent placeholder() {
        return new AssistantMessageContent(null, null, null, List.of(), null, null);
    }

    public static AssistantMessageContent failed(AssistantFailure failure) {
        return new AssistantMessageContent(null, null, null, List.of(), failure, null);
    }

    public static AssistantMessageContent canceled(AssistantCancel cancel) {
        return new AssistantMessageContent(null, null, null, List.of(), null, cancel);
    }
}
