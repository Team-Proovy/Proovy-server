package com.proovy.domain.run.brain;

import com.fasterxml.jackson.annotation.JsonValue;
import com.proovy.domain.run.entity.WireValue;

/** Brain runtime record에 도착하는 operation. */
public enum RunOperation implements WireValue {
    STREAM("stream"),
    STATUS("status"),
    CANCEL("cancel"),
    CLEANUP("cleanup");

    private final String wireValue;

    RunOperation(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
