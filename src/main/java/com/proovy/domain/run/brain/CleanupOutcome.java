package com.proovy.domain.run.brain;

import com.fasterxml.jackson.annotation.JsonValue;
import com.proovy.domain.run.entity.WireValue;

/** runtime cleanup 결과. protected는 활성·정리 중 실행을 건드리지 않았다는 뜻이다. */
public enum CleanupOutcome implements WireValue {
    CLEANED("cleaned"),
    NOOP("noop"),
    PROTECTED("protected");

    private final String wireValue;

    CleanupOutcome(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
