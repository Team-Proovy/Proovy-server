package com.proovy.domain.run.contract;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

/**
 * GET /api/ai-runs/{runId} result. attempt·fence·deadline·usage는 노출하지 않는다.
 *
 * @param lastSeq             그 시점 마지막 durable seq. 그대로 cursor로 보내면 그 뒤부터 replay한다.
 * @param responseDisposition COMPLETED일 때만 값이 있다(terminal-v2 ResponseDisposition wire 값).
 * @param failure             FAILED일 때만 값이 있다.
 */
public record RunStatus(
        String runId,
        String requestId,
        String noteId,
        String userMessageId,
        String assistantMessageId,
        PublicRunStatus status,
        boolean cancelable,
        long lastSeq,
        String responseDisposition,
        RunFailureSummary failure,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant completedAt
) {
}
