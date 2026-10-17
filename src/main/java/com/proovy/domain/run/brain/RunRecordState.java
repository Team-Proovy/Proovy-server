package com.proovy.domain.run.brain;

import com.fasterxml.jackson.annotation.JsonValue;
import com.proovy.domain.run.entity.WireValue;

/** Brain runtime record 상태. status·cancel·cleanup 응답과 409 conflict의 state. */
public enum RunRecordState implements WireValue {
    ABSENT("absent"),
    EXECUTING("executing"),
    CANCELING("canceling"),
    TERMINAL("terminal");

    private final String wireValue;

    RunRecordState(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
