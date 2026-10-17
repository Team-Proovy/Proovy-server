package com.proovy.domain.run.contract;

public record ResponsePageCompletedPayload(String assistantMessageId, String responsePageId, int pageIndex) {
}
