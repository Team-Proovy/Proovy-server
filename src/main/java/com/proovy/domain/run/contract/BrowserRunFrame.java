package com.proovy.domain.run.contract;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * SSE data로 보내는 durable frame. SSE {@code id}는 {@link RunEventCursor} 형식
 * {@code {runId}:{seq}}이고 {@code event}는 type이다.
 * <p>
 * Server 소유 type(run.created·response.page.*·run.canceling·run.canceled)의 payload는
 * {@link ServerFrameType}의 record로, activity.*·run.completed·run.failed는 Brain
 * terminal-v2/activity-v1 payload를 그대로 전달한다.
 */
public record BrowserRunFrame(
        String contractVersion,
        String runId,
        long seq,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt,
        String type,
        JsonNode payload
) {

    public BrowserRunFrame {
        occurredAt = BrowserRunContract.wireTime(occurredAt);
    }
}
