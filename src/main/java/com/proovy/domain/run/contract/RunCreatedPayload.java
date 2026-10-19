package com.proovy.domain.run.contract;

import java.util.List;

/**
 * seq 1 frame. requestedOutputs는 접수된 값이므로 text만 있다.
 *
 * @param status 항상 PENDING
 */
public record RunCreatedPayload(
        String requestId,
        String noteId,
        String userMessageId,
        String assistantMessageId,
        PublicRunStatus status,
        List<RequestedTask> requestedTasks,
        List<RequestedOutput> requestedOutputs
) {
}
