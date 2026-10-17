package com.proovy.domain.run.brain;

import static com.proovy.domain.run.brain.InternalRunContract.MAX_SOURCE_EVENT_ID_LENGTH;
import static com.proovy.domain.run.brain.InternalRunContract.SOURCE_CONTRACT_VERSION;
import static com.proovy.domain.run.brain.InternalRunContract.SOURCE_EVENT_ID;
import static com.proovy.domain.run.brain.InternalRunContract.atLeast;
import static com.proovy.domain.run.brain.InternalRunContract.fenceToken;
import static com.proovy.domain.run.brain.InternalRunContract.matching;
import static com.proovy.domain.run.brain.InternalRunContract.opaqueId;
import static com.proovy.domain.run.brain.InternalRunContract.require;
import static com.proovy.domain.run.brain.InternalRunContract.violation;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

/** brain-source-v1 envelope 공통 검사({@link SourceEvent}·{@link StoredTerminal}). */
final class SourceEnvelope {

    private SourceEnvelope() {
    }

    static void checkVersion(String contractVersion) {
        if (!SOURCE_CONTRACT_VERSION.equals(contractVersion)) {
            throw violation("contract_version", "must be " + SOURCE_CONTRACT_VERSION);
        }
    }

    static String runId(String runId) {
        return opaqueId(runId, "run_id");
    }

    static void attempt(int attempt) {
        atLeast(attempt, 1, "attempt");
    }

    static String fence(String fenceToken) {
        return fenceToken(fenceToken, "fence_token");
    }

    static String sourceEventId(String sourceEventId) {
        return matching(sourceEventId, "source_event_id", SOURCE_EVENT_ID, MAX_SOURCE_EVENT_ID_LENGTH);
    }

    static void occurredAt(OffsetDateTime occurredAt) {
        require(occurredAt, "occurred_at");
    }

    static void payload(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw violation("payload", "must be an object");
        }
    }
}
