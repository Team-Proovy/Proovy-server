package com.proovy.domain.run.brain;

import com.fasterxml.jackson.annotation.JsonValue;
import com.proovy.domain.run.entity.WireValue;

/** history message의 화자. */
public enum HistoryRole implements WireValue {
    USER("user"),
    ASSISTANT("assistant");

    private final String wireValue;

    HistoryRole(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
