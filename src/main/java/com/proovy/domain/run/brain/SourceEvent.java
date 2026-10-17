package com.proovy.domain.run.brain;

import static com.proovy.domain.run.brain.InternalRunContract.require;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

/**
 * stream으로 받은 brain-source-v1 event({@code SourceEvent} union: {@code activity.*}·{@code run.completed}·
 * {@code run.failed}). 저장 전용 {@code run.canceled}는 type 단계에서 거부된다({@link SourceEventType}).
 * <p>
 * envelope는 typed로 검사하고, Brain 소유 payload(activity·terminal 본문)는 JSON 그대로 둔다.
 * payload 의미 검사(terminal provenance·activity 규칙)와 현재 fence 판정은 소비자(1.80·1.81·1.83)의 몫이다.
 * attempt·fence는 요청과 다를 수 있다 — Brain tail은 새 attempt가 record를 넘겨받으면 그 epoch를 따라간다.
 */
public record SourceEvent(
        String contractVersion,
        String runId,
        @JsonProperty(required = true) int attempt,
        String fenceToken,
        String sourceEventId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) OffsetDateTime occurredAt,
        SourceEventType type,
        JsonNode payload) {

    public SourceEvent {
        SourceEnvelope.checkVersion(contractVersion);
        runId = SourceEnvelope.runId(runId);
        SourceEnvelope.attempt(attempt);
        fenceToken = SourceEnvelope.fence(fenceToken);
        sourceEventId = SourceEnvelope.sourceEventId(sourceEventId);
        SourceEnvelope.occurredAt(occurredAt);
        require(type, "type");
        SourceEnvelope.payload(payload);
    }

    public boolean isTerminal() {
        return type.isTerminal();
    }
}
