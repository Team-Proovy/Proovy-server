package com.proovy.domain.run.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.proovy.domain.run.entity.AiRunEventType;
import java.util.Objects;

/**
 * terminal transaction이 한꺼번에 저장하는 event 하나({@link RunEventStore#appendTerminalEvents}).
 * page 경계와 완료 event는 이 경로로만 만든다.
 *
 * @param sourceEventId run.completed·run.failed가 Brain terminal의 source_event_id를 남길 때만(attempt와 함께). Server 소유
 *                      event(response.page.*·run.canceled)는 null이다(ck_ai_run_events_server_owned).
 */
public record TerminalRunEvent(AiRunEventType type, JsonNode payload, String sourceEventId, Integer attempt) {

    public TerminalRunEvent {
        Objects.requireNonNull(type, "type");
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("payload must be an object");
        }
        if ((sourceEventId == null) != (attempt == null)) {
            throw new IllegalArgumentException("sourceEventId and attempt go together");
        }
        if (sourceEventId != null && type != AiRunEventType.RUN_COMPLETED && type != AiRunEventType.RUN_FAILED) {
            throw new IllegalArgumentException("only Brain terminals carry a source event id");
        }
    }

    /** {@code {assistantMessageId, responsePageId, presentation, title?, pageIndex}} */
    public static TerminalRunEvent pageStarted(JsonNode payload) {
        return new TerminalRunEvent(AiRunEventType.RESPONSE_PAGE_STARTED, payload, null, null);
    }

    /** {@code {assistantMessageId, responsePageId, pageIndex}} */
    public static TerminalRunEvent pageCompleted(JsonNode payload) {
        return new TerminalRunEvent(AiRunEventType.RESPONSE_PAGE_COMPLETED, payload, null, null);
    }

    public static TerminalRunEvent runCompleted(JsonNode payload) {
        return new TerminalRunEvent(AiRunEventType.RUN_COMPLETED, payload, null, null);
    }

    public static TerminalRunEvent runCompleted(JsonNode payload, String sourceEventId, int attempt) {
        return new TerminalRunEvent(AiRunEventType.RUN_COMPLETED, payload, sourceEventId, attempt);
    }

    public static TerminalRunEvent runFailed(JsonNode payload) {
        return new TerminalRunEvent(AiRunEventType.RUN_FAILED, payload, null, null);
    }

    public static TerminalRunEvent runFailed(JsonNode payload, String sourceEventId, int attempt) {
        return new TerminalRunEvent(AiRunEventType.RUN_FAILED, payload, sourceEventId, attempt);
    }

    public static TerminalRunEvent runCanceled(JsonNode payload) {
        return new TerminalRunEvent(AiRunEventType.RUN_CANCELED, payload, null, null);
    }

    boolean isPageBoundary() {
        return type == AiRunEventType.RESPONSE_PAGE_STARTED || type == AiRunEventType.RESPONSE_PAGE_COMPLETED;
    }
}
