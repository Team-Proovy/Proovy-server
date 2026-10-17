package com.proovy.domain.run.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * assistant 메시지 본문. assistantResponse·verificationSummary는 Brain terminal-v2 계약
 * (AssistantResponseV1·SolutionVerificationSummary)을 그대로 전달하므로 JSON 그대로 둔다.
 * PENDING/STREAMING placeholder도 이 객체를 두되 모든 값은 null, verificationSummary는 빈 배열이다
 * ({@link #placeholder()}) — Candidate 본문은 공개하지 않는다.
 */
public record AssistantMessageContent(
        String responseDisposition,
        String clarificationReason,
        JsonNode assistantResponse,
        List<JsonNode> verificationSummary,
        AssistantFailure failure,
        AssistantCancel cancel
) {

    public static AssistantMessageContent placeholder() {
        return new AssistantMessageContent(null, null, null, List.of(), null, null);
    }
}
