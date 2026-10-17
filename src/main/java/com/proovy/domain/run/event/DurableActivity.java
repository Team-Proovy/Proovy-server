package com.proovy.domain.run.event;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proovy.domain.run.entity.AiRunEventType;
import java.time.Instant;
import java.util.UUID;

/**
 * 검사를 통과한 source Activity 하나. {@link SourceActivities#toDurable}만 만든다.
 *
 * @param sourceEventId 저장 dedup 키({@link SourceActivities#storedSourceEventId}: 128자 초과 ID는 digest)
 * @param payload    browser camelCase payload(activityId·kind·kind별 허용 필드). 이 값만 저장·공개한다.
 * @param occurredAt source {@code occurred_at}(µs). 저장 시 {@code ai_run_events.created_at}이 되고 frame {@code occurredAt}으로 나간다.
 */
public record DurableActivity(
        UUID runId,
        int attempt,
        String fenceToken,
        String sourceEventId,
        AiRunEventType type,
        ActivityKind kind,
        String activityId,
        ObjectNode payload,
        Instant occurredAt) {

    public boolean isActivityTerminal() {
        return type == AiRunEventType.ACTIVITY_COMPLETED || type == AiRunEventType.ACTIVITY_FAILED
                || type == AiRunEventType.ACTIVITY_CANCELED;
    }
}
