package com.proovy.domain.run.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.proovy.domain.run.contract.BrowserRunContract;
import com.proovy.domain.run.contract.BrowserRunFrame;
import com.proovy.domain.run.contract.RunEventCursor;
import com.proovy.domain.run.entity.AiRunEventType;
import java.time.Instant;
import java.util.UUID;

/**
 * commit된 durable event 하나(replay 조회 결과). source 전용 값(source_event_id·attempt·fence)은 담지 않는다.
 *
 * @param payload    저장된 payload. activity.*·run.created·run.canceling·run.canceled는 그대로 frame payload이고,
 *                   response.page.*·run.completed·run.failed는 1.82가 저장한 최소 요약이라 replay(1.86)가 canonical
 *                   message로 채운다.
 * @param occurredAt frame {@code occurredAt} ({@code ai_run_events.created_at})
 */
public record StoredRunEvent(
        UUID runId,
        long seq,
        AiRunEventType type,
        String activityId,
        JsonNode payload,
        Instant occurredAt) {

    /** SSE {@code id} 겸 재접속 cursor {@code {runId}:{seq}}. */
    public RunEventCursor cursor() {
        return new RunEventCursor(runId.toString(), seq);
    }

    /** 저장 payload 그대로의 durable frame. hydration이 필요한 type은 호출자가 payload를 바꿔 만든다. */
    public BrowserRunFrame toFrame() {
        return toFrame(payload);
    }

    public BrowserRunFrame toFrame(JsonNode framePayload) {
        return new BrowserRunFrame(BrowserRunContract.FRAME_CONTRACT_VERSION, runId.toString(), seq, occurredAt,
                type.wireValue(), framePayload);
    }
}
