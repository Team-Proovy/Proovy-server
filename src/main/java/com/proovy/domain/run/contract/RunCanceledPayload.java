package com.proovy.domain.run.contract;

public record RunCanceledPayload(
        String assistantMessageId,
        PublicRunStatus status,
        CancelCause cause,
        String message
) {

    public static RunCanceledPayload of(String assistantMessageId, CancelCause cause) {
        return new RunCanceledPayload(assistantMessageId, PublicRunStatus.CANCELED, cause, cause.message());
    }
}
