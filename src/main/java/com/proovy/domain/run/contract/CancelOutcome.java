package com.proovy.domain.run.contract;

import com.fasterxml.jackson.annotation.JsonValue;

/** POST /api/ai-runs/{runId}/cancel 결과. cancel은 멱등이다. */
public enum CancelOutcome {
    ACCEPTED("accepted"),
    ALREADY_CANCELING("already_canceling"),
    ALREADY_TERMINAL("already_terminal");

    private final String wireValue;

    CancelOutcome(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
