package com.proovy.domain.run.brain;

import static com.proovy.domain.run.brain.InternalRunContract.CONTRACT_VERSION;
import static com.proovy.domain.run.brain.InternalRunContract.opaqueId;
import static com.proovy.domain.run.brain.InternalRunContract.require;
import static com.proovy.domain.run.brain.InternalRunContract.violation;

/** cleanup 결과와 판정 시점의 record state. */
public record RuntimeCleanupResponse(String contractVersion, String runId, CleanupOutcome outcome,
        RunRecordState state) {

    public RuntimeCleanupResponse {
        if (!CONTRACT_VERSION.equals(contractVersion)) {
            throw violation("contract_version", "must be " + CONTRACT_VERSION);
        }
        runId = opaqueId(runId, "run_id");
        require(outcome, "outcome");
        require(state, "state");
    }
}
