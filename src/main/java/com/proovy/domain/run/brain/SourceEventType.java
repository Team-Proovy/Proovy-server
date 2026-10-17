package com.proovy.domain.run.brain;

import com.fasterxml.jackson.annotation.JsonValue;
import com.proovy.domain.run.entity.WireValue;

/**
 * brain-source-v1 {@code SourceEvent} union의 type. stream으로 오는 값은 이것뿐이다.
 * {@code run.canceled}는 저장 전용 variant라 여기 없고 {@link StoredTerminalType}에만 있다.
 */
public enum SourceEventType implements WireValue {
    ACTIVITY_STARTED("activity.started"),
    ACTIVITY_UPDATED("activity.updated"),
    ACTIVITY_COMPLETED("activity.completed"),
    ACTIVITY_FAILED("activity.failed"),
    ACTIVITY_CANCELED("activity.canceled"),
    RUN_COMPLETED("run.completed"),
    RUN_FAILED("run.failed");

    private final String wireValue;

    SourceEventType(String wireValue) {
        this.wireValue = wireValue;
    }

    public boolean isTerminal() {
        return this == RUN_COMPLETED || this == RUN_FAILED;
    }

    @Override
    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
