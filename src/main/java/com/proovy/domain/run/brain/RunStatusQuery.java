package com.proovy.domain.run.brain;

import static com.proovy.domain.run.brain.InternalRunContract.CONTRACT_VERSION;
import static com.proovy.domain.run.brain.InternalRunContract.atLeast;
import static com.proovy.domain.run.brain.InternalRunContract.opaqueId;
import static com.proovy.domain.run.brain.InternalRunContract.violation;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code GET /internal/v1/runs/{run_id}?attempt=&fence_token=} 조회. 관찰 전용이라 요청 본문이 없고
 * 실행을 시작하거나 attempt를 만들지 못한다. Server가 현재로 보는 attempt·fence를 함께 보낸다.
 */
public record RunStatusQuery(String contractVersion, String runId, @JsonProperty(required = true) int attempt, String fenceToken) {

    public RunStatusQuery {
        if (!CONTRACT_VERSION.equals(contractVersion)) {
            throw violation("contract_version", "must be " + CONTRACT_VERSION);
        }
        runId = opaqueId(runId, "run_id");
        atLeast(attempt, 1, "attempt");
        fenceToken = InternalRunContract.fenceToken(fenceToken, "fence_token");
    }

    public static RunStatusQuery of(String runId, int attempt, String fenceToken) {
        return new RunStatusQuery(CONTRACT_VERSION, runId, attempt, fenceToken);
    }
}
