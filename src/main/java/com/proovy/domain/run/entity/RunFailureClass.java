package com.proovy.domain.run.entity;

/** terminal-v2 failure_class. code와의 대응은 DB CHECK(ck_ai_runs_failure_code_class)가 강제한다. */
public enum RunFailureClass implements WireValue {
    VERIFICATION("verification"),
    BUDGET("budget"),
    INFRASTRUCTURE("infrastructure"),
    ATTACHMENT("attachment");

    private final String wireValue;

    RunFailureClass(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    public String wireValue() {
        return wireValue;
    }

    public static class Converter extends WireValue.Converter<RunFailureClass> {
        public Converter() {
            super(RunFailureClass.class);
        }
    }
}
