package com.proovy.domain.run.brain;

import static com.proovy.domain.run.brain.InternalRunContract.CONTRACT_VERSION;
import static com.proovy.domain.run.brain.InternalRunContract.atLeast;
import static com.proovy.domain.run.brain.InternalRunContract.opaqueId;
import static com.proovy.domain.run.brain.InternalRunContract.require;
import static com.proovy.domain.run.brain.InternalRunContract.violation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.proovy.domain.run.entity.RunCancelCause;

/** {@code POST /internal/v1/runs/{run_id}/cancel} — 현재 fence의 명시적 취소. */
public record RunCancelRequest(String contractVersion, String runId, @JsonProperty(required = true) int attempt, String fenceToken,
        RunCancelCause cause) {

    public RunCancelRequest {
        if (!CONTRACT_VERSION.equals(contractVersion)) {
            throw violation("contract_version", "must be " + CONTRACT_VERSION);
        }
        runId = opaqueId(runId, "run_id");
        atLeast(attempt, 1, "attempt");
        fenceToken = InternalRunContract.fenceToken(fenceToken, "fence_token");
        require(cause, "cause");
    }

    public static RunCancelRequest of(String runId, int attempt, String fenceToken, RunCancelCause cause) {
        return new RunCancelRequest(CONTRACT_VERSION, runId, attempt, fenceToken, cause);
    }
}
