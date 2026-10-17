package com.proovy.domain.run.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * assistant 메시지 본문. assistantResponse·verificationSummary는 Brain terminal-v2 계약
 * (AssistantResponseV1·SolutionVerificationSummary)을 그대로 전달하므로 JSON 그대로 둔다.
 * PENDING/STREAMING placeholder는 본문이 없다(Candidate 비공개).
 */
public record AssistantMessageContent(
        String responseDisposition,
        String clarificationReason,
        JsonNode assistantResponse,
        List<JsonNode> verificationSummary,
        AssistantFailure failure,
        AssistantCancel cancel
) {
}
