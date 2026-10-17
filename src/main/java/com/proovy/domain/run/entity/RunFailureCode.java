package com.proovy.domain.run.entity;

/** terminal-v2 run.failed code. */
public enum RunFailureCode implements WireValue {
    VERIFICATION_FAILED("verification_failed"),
    BUDGET_EXCEEDED("budget_exceeded"),
    PROVIDER_ERROR("provider_error"),
    TIMEOUT("timeout"),
    STORAGE_ERROR("storage_error"),
    INTERNAL_ERROR("internal_error"),
    INVALID_ATTACHMENT("invalid_attachment");

    private final String wireValue;

    RunFailureCode(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    public String wireValue() {
        return wireValue;
    }

    public static class Converter extends WireValue.Converter<RunFailureCode> {
        public Converter() {
            super(RunFailureCode.class);
        }
    }
}
