package com.proovy.domain.run.brain;

import static com.proovy.domain.run.brain.InternalRunContract.CONTRACT_VERSION;
import static com.proovy.domain.run.brain.InternalRunContract.opaqueId;
import static com.proovy.domain.run.brain.InternalRunContract.violation;

/**
 * {@code POST /internal/v1/runs/{run_id}/cleanup}. Server가 terminal을 저장했다고 확인한 뒤에만 보낸다
 * (이 호출 자체가 저장 확인으로 기록된다). 멱등이고 활성 실행은 protected로 남는다.
 */
public record RuntimeCleanupRequest(String contractVersion, String runId) {

    public RuntimeCleanupRequest {
        if (!CONTRACT_VERSION.equals(contractVersion)) {
            throw violation("contract_version", "must be " + CONTRACT_VERSION);
        }
        runId = opaqueId(runId, "run_id");
    }

    public static RuntimeCleanupRequest of(String runId) {
        return new RuntimeCleanupRequest(CONTRACT_VERSION, runId);
    }
}
