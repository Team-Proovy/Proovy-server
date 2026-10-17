package com.proovy.domain.run.contract;

import com.fasterxml.jackson.annotation.JsonValue;

/** 브라우저가 명시할 수 있는 작업. 자연어 의도(예: 변형 문제)는 wire enum이 아니다. */
public enum RequestedTask {
    SOLVE("solve"),
    CHECK("check"),
    EXPLAIN("explain");

    private final String wireValue;

    RequestedTask(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
