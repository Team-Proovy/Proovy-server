package com.proovy.domain.usage.entity;

/**
 * request당 최대 한 번인 Usage Compensation의 사유 (docs/plan/07).
 * RUNNING·RETRYING 이후 사용자 취소와 substantive 응답은 보상하지 않으므로 여기에 없다.
 */
public enum UsageCompensationReason {
    PENDING_CANCELED,
    CLARIFICATION_ONLY,
    UNSUPPORTED_ONLY,
    VERIFICATION_FAILED,
    BUDGET_EXCEEDED,
    INVALID_ATTACHMENT,
    INFRASTRUCTURE_FAILURE
}
