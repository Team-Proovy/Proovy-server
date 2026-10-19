package com.proovy.domain.usage.service;

import com.proovy.domain.usage.entity.UsageCompensationReason;

/**
 * @param outcome 보상 처리 결과
 * @param reason  COMPENSATED면 이번 사유, ALREADY_COMPENSATED면 먼저 적용된 사유, 그 외 null
 */
public record UsageCompensationResult(Outcome outcome, UsageCompensationReason reason) {

    public enum Outcome {
        /** 이번 호출이 보상 row를 만들고 잔액을 복구했다 */
        COMPENSATED,
        /** 같은 request가 이미 보상되었다(사유 경합 포함). 잔액은 바뀌지 않았다 */
        ALREADY_COMPENSATED,
        /** 정책상 차감을 유지한다 (substantive, RUNNING·RETRYING 이후 사용자 취소) */
        NOT_COMPENSABLE,
        /** 차감 row가 없어 보상할 것이 없다 */
        NOT_CHARGED
    }

    static UsageCompensationResult of(Outcome outcome, UsageCompensationReason reason) {
        return new UsageCompensationResult(outcome, reason);
    }

    public boolean compensatedNow() {
        return outcome == Outcome.COMPENSATED;
    }
}
