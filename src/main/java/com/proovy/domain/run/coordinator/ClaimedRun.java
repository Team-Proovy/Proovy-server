package com.proovy.domain.run.coordinator;

import java.util.Objects;
import java.util.UUID;

/**
 * coordinator가 조건부 UPDATE로 배정한 run의 현재 attempt·fence. 이 값으로만 Brain에 전달하고 lease를 갱신한다.
 */
public record ClaimedRun(UUID runId, int attempt, String fenceToken) {

    public ClaimedRun {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(fenceToken, "fenceToken");
        if (attempt < 1) {
            throw new IllegalArgumentException("a claimed run has attempt >= 1");
        }
    }
}
