package com.proovy.domain.run.brain;

import com.fasterxml.jackson.annotation.JsonValue;
import com.proovy.domain.run.entity.WireValue;

/** {@link RunDecisions} 판정 결과(Brain {@code decide_run_request}). */
public enum RunDecision implements WireValue {
    START("start"),
    OBSERVE("observe"),
    RETURN_TERMINAL("return_terminal"),
    REPORT("report"),
    ACCEPT_CANCEL("accept_cancel"),
    ACK_CANCEL("ack_cancel"),
    CLEAN("clean"),
    NOOP("noop"),
    REJECT("reject");

    private final String wireValue;

    RunDecision(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
