package com.proovy.domain.run.contract;

public record AssistantCancel(CancelCause cause, String message) {

    public static AssistantCancel of(CancelCause cause) {
        return new AssistantCancel(cause, cause.message());
    }
}
