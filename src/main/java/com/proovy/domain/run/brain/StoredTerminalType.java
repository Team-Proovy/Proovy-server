package com.proovy.domain.run.brain;

import com.fasterxml.jackson.annotation.JsonValue;
import com.proovy.domain.run.entity.WireValue;

/**
 * status 응답 {@code terminal}의 type. stream union과 달리 저장 전용 {@code run.canceled}를 포함한다.
 */
public enum StoredTerminalType implements WireValue {
    RUN_COMPLETED("run.completed"),
    RUN_FAILED("run.failed"),
    RUN_CANCELED("run.canceled");

    private final String wireValue;

    StoredTerminalType(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
