package com.proovy.domain.run.brain;

import com.fasterxml.jackson.annotation.JsonValue;
import com.proovy.domain.run.entity.WireValue;

/** Verified Solution 참조의 수명주기 상태(fingerprint 제외). */
public enum SolutionReferenceStatus implements WireValue {
    ACTIVE("active"),
    SUPERSEDED("superseded");

    private final String wireValue;

    SolutionReferenceStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
