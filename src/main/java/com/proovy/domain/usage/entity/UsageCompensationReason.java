package com.proovy.domain.usage.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * request당 최대 한 번인 Usage Compensation의 사유 (docs/plan/07).
 * RUNNING·RETRYING 이후 사용자 취소와 substantive 응답은 보상하지 않으므로 여기에 없다.
 * 결정표는 {@code com.proovy.domain.usage.service.UsageCompensationPolicy}에 있다.
 */
@Getter
@RequiredArgsConstructor
public enum UsageCompensationReason {
    PENDING_CANCELED("시작 전 취소"),
    CLARIFICATION_ONLY("추가 정보 요청 응답"),
    UNSUPPORTED_ONLY("지원하지 않는 요청 안내"),
    VERIFICATION_FAILED("검증 실패"),
    BUDGET_EXCEEDED("처리 한도 초과"),
    INVALID_ATTACHMENT("첨부 검증 실패"),
    INFRASTRUCTURE_FAILURE("일시적 처리 실패");

    /** 사용자 크레딧 내역에 보이는 보상 사유 */
    private final String description;
}
