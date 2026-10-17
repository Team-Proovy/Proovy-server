package com.proovy.domain.run.brain;

import static com.proovy.domain.run.brain.InternalRunContract.CONTRACT_VERSION;
import static com.proovy.domain.run.brain.InternalRunContract.opaqueId;
import static com.proovy.domain.run.brain.InternalRunContract.require;
import static com.proovy.domain.run.brain.InternalRunContract.violation;

/** cancel acknowledgment. 정리(wind-down)는 Brain 쪽에서 계속되고 Server는 이후 status로 확인한다. */
public record RunCancelAck(String contractVersion, String runId, CancelOutcome outcome, RunRecordState state) {

    public RunCancelAck {
        if (!CONTRACT_VERSION.equals(contractVersion)) {
            throw violation("contract_version", "must be " + CONTRACT_VERSION);
        }
        runId = opaqueId(runId, "run_id");
        require(outcome, "outcome");
        require(state, "state");
    }
}
