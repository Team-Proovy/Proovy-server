package com.proovy.domain.run.brain;

import com.fasterxml.jackson.annotation.JsonValue;
import com.proovy.domain.run.entity.WireValue;

/** 참조 claim의 coverage. */
public enum SummaryCoverage implements WireValue {
    DIRECT("direct"),
    SUPPORTING("supporting");

    private final String wireValue;

    SummaryCoverage(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
