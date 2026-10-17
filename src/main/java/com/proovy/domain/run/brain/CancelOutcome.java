package com.proovy.domain.run.brain;

import com.fasterxml.jackson.annotation.JsonValue;
import com.proovy.domain.run.entity.WireValue;

/** internal cancel acknowledgment 결과. 브라우저 계약의 {@code run.contract.CancelOutcome}과 다르다(unknown_run 포함). */
public enum CancelOutcome implements WireValue {
    ACCEPTED("accepted"),
    ALREADY_CANCELING("already_canceling"),
    ALREADY_TERMINAL("already_terminal"),
    UNKNOWN_RUN("unknown_run");

    private final String wireValue;

    CancelOutcome(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
