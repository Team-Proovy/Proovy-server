package com.proovy.domain.run.contract;

/**
 * @param code   terminal-v2 FailureCode wire 값
 * @param reason invalid_attachment일 때의 AttachmentFailureReason, 그 외 null
 */
public record RunFailureSummary(String code, String reason, boolean recoverable) {
}
