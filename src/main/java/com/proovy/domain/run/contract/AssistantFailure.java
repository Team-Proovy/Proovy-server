package com.proovy.domain.run.contract;

public record AssistantFailure(String code, String reason, String message, boolean recoverable) {
}
