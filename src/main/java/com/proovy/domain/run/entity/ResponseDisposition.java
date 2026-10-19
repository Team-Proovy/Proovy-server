package com.proovy.domain.run.entity;

/** terminal-v2 response_disposition. DB에는 wire 값(소문자)으로 저장한다. */
public enum ResponseDisposition implements WireValue {
    SUBSTANTIVE("substantive"),
    CLARIFICATION_ONLY("clarification_only"),
    UNSUPPORTED_ONLY("unsupported_only");

    private final String wireValue;

    ResponseDisposition(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    public String wireValue() {
        return wireValue;
    }

    public static class Converter extends WireValue.Converter<ResponseDisposition> {
        public Converter() {
            super(ResponseDisposition.class);
        }
    }
}
