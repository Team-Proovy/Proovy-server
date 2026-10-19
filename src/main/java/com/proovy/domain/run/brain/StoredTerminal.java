package com.proovy.domain.run.brain;

import static com.proovy.domain.run.brain.InternalRunContract.require;
import static com.proovy.domain.run.brain.InternalRunContract.violation;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.proovy.domain.run.entity.RunCancelCause;
import java.time.OffsetDateTime;

/**
 * status 응답의 저장 terminal({@code run.completed}·{@code run.failed}·저장 전용 {@code run.canceled}).
 * stream union과 달리 {@code run.canceled}를 허용하고, 그 payload는 {@code {"cause": ...}}만 가진다.
 * completed·failed payload는 JSON 그대로 두고 의미 검사는 terminal 소비자(1.81)가 한다.
 */
public record StoredTerminal(
        String contractVersion,
        String runId,
        @JsonProperty(required = true) int attempt,
        String fenceToken,
        String sourceEventId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) OffsetDateTime occurredAt,
        StoredTerminalType type,
        JsonNode payload) {

    public StoredTerminal {
        SourceEnvelope.checkVersion(contractVersion);
        runId = SourceEnvelope.runId(runId);
        SourceEnvelope.attempt(attempt);
        fenceToken = SourceEnvelope.fence(fenceToken);
        sourceEventId = SourceEnvelope.sourceEventId(sourceEventId);
        SourceEnvelope.occurredAt(occurredAt);
        require(type, "type");
        SourceEnvelope.payload(payload);
        if (type == StoredTerminalType.RUN_CANCELED) {
            if (payload.size() != 1 || cancelCause(payload) == null) {
                throw violation("payload", "run.canceled carries exactly a known cause");
            }
        }
    }

    /** {@code run.canceled}의 원인. 다른 type이면 null. */
    public RunCancelCause canceledCause() {
        return type == StoredTerminalType.RUN_CANCELED ? cancelCause(payload) : null;
    }

    private static RunCancelCause cancelCause(JsonNode payload) {
        JsonNode cause = payload.get("cause");
        if (cause == null || !cause.isTextual()) {
            return null;
        }
        for (RunCancelCause candidate : RunCancelCause.values()) {
            if (candidate.wireValue().equals(cause.textValue())) {
                return candidate;
            }
        }
        return null;
    }
}
