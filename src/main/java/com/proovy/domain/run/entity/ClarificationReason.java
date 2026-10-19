package com.proovy.domain.run.entity;

/** terminal-v2 clarification_reason (clarification_only일 때만). */
public enum ClarificationReason implements WireValue {
    MISSING_INPUT("missing_input"),
    AMBIGUOUS_TARGET("ambiguous_target"),
    LIMITED_PAGES("limited_pages"),
    CORPUS_NOT_READY("corpus_not_ready"),
    PAGE_RANGE_REQUIRED("page_range_required"),
    PAGE_RANGE_UNREADABLE("page_range_unreadable");

    private final String wireValue;

    ClarificationReason(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    public String wireValue() {
        return wireValue;
    }

    public static class Converter extends WireValue.Converter<ClarificationReason> {
        public Converter() {
            super(ClarificationReason.class);
        }
    }
}
