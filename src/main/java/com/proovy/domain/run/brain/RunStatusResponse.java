package com.proovy.domain.run.brain;

import static com.proovy.domain.run.brain.InternalRunContract.CONTRACT_VERSION;
import static com.proovy.domain.run.brain.InternalRunContract.SHA256_HEX;
import static com.proovy.domain.run.brain.InternalRunContract.matching;
import static com.proovy.domain.run.brain.InternalRunContract.opaqueId;
import static com.proovy.domain.run.brain.InternalRunContract.require;
import static com.proovy.domain.run.brain.InternalRunContract.violation;

/**
 * status 응답. absent면 실행 사실이 없고, 그 밖에는 record의 attempt·fence·fingerprint를 담으며
 * terminal state일 때만 저장 terminal을 재전달한다(재계산 없음).
 */
public record RunStatusResponse(
        String contractVersion,
        String runId,
        RunRecordState state,
        Integer attempt,
        String fenceToken,
        String requestFingerprint,
        StoredTerminal terminal) {

    public RunStatusResponse {
        if (!CONTRACT_VERSION.equals(contractVersion)) {
            throw violation("contract_version", "must be " + CONTRACT_VERSION);
        }
        runId = opaqueId(runId, "run_id");
        require(state, "state");
        if (attempt != null && attempt < 1) {
            throw violation("attempt", "is out of range");
        }
        if (fenceToken != null) {
            fenceToken = InternalRunContract.fenceToken(fenceToken, "fence_token");
        }
        if (requestFingerprint != null) {
            requestFingerprint = matching(requestFingerprint, "request_fingerprint", SHA256_HEX, 64);
        }
        if (state == RunRecordState.ABSENT) {
            if (attempt != null || fenceToken != null || requestFingerprint != null || terminal != null) {
                throw violation("state", "absent record carries no execution facts");
            }
        } else {
            if (attempt == null || fenceToken == null || requestFingerprint == null) {
                throw violation("state", "live record names its attempt, fence and fingerprint");
            }
            if ((state == RunRecordState.TERMINAL) != (terminal != null)) {
                throw violation("terminal", "is present exactly for a terminal record");
            }
        }
        if (terminal != null && !terminal.runId().equals(runId)) {
            throw violation("terminal.run_id", "must equal run_id");
        }
    }

    /** 이 record가 {@code fingerprint}의 요청으로 만들어졌는지. absent면 false. */
    public boolean matches(InternalRunFingerprint fingerprint) {
        return fingerprint.sha256().equals(requestFingerprint);
    }
}
