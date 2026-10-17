package com.proovy.domain.run.entity;

/** internal-run-v1 CancelCause. */
public enum RunCancelCause implements WireValue {
    USER_REQUESTED("user_requested"),
    ASSET_DELETING("asset_deleting"),
    BUDGET_EXHAUSTED("budget_exhausted");

    private final String wireValue;

    RunCancelCause(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    public String wireValue() {
        return wireValue;
    }

    public static class Converter extends WireValue.Converter<RunCancelCause> {
        public Converter() {
            super(RunCancelCause.class);
        }
    }
}
