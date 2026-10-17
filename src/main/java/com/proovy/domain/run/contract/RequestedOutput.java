package com.proovy.domain.run.contract;

import com.fasterxml.jackson.annotation.JsonValue;

/** video는 알려진 값이지만 Phase 1 미지원이다(RUN4002). */
public enum RequestedOutput {
    TEXT("text"),
    VIDEO("video");

    private final String wireValue;

    RequestedOutput(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
