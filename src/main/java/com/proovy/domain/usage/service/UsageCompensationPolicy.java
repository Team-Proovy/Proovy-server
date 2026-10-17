package com.proovy.domain.usage.service;

import com.proovy.domain.run.entity.AiRunStatus;
import com.proovy.domain.run.entity.RunCancelCause;
import com.proovy.domain.usage.entity.UsageCompensationReason;

import java.util.Optional;

/**
 * 일반 요청 사용량 보상 결정표 (docs/plan/05·07·12, spec 07). 접수 시 한 단위를 선차감하고,
 * terminal 분류가 아래 보상 대상이면 같은 terminal transaction에서 한 번 보상한다.
 *
 * <pre>
 * terminal   분류                                         순사용량  보상 사유
 * COMPLETED  substantive (혼합 결과·자료 부재 안내 포함)      1        —
 * COMPLETED  clarification_only (모든 clarification_reason)  0        CLARIFICATION_ONLY
 * COMPLETED  unsupported_only                            0        UNSUPPORTED_ONLY
 * FAILED     verification                                0        VERIFICATION_FAILED
 * FAILED     budget                                      0        BUDGET_EXCEEDED
 * FAILED     attachment                                  0        INVALID_ATTACHMENT
 * FAILED     infrastructure (provider·timeout·storage·internal) 0  INFRASTRUCTURE_FAILURE
 * CANCELED   PENDING에서 취소 (user_requested·asset_deleting·budget_exhausted) 0  PENDING_CANCELED
 * CANCELED   RUNNING·RETRYING에서 user_requested·asset_deleting                 1  —
 * CANCELED   RUNNING·RETRYING에서 budget_exhausted                              0  BUDGET_EXCEEDED
 * </pre>
 *
 * <p>FAILED terminal은 substantive 답을 저장하지 않으므로(terminal-v2) 모든 최종 실패가 보상 대상이다.
 * budget_exhausted 취소는 사용자 취소가 아니라 공개 후보 없이 요청 예산이 소진된 것이므로 예산 실패와 같이 보상한다.
 */
public final class UsageCompensationPolicy {

    private UsageCompensationPolicy() {
    }

    /** 보상 대상이면 사유, 차감을 유지하면 empty. */
    public static Optional<UsageCompensationReason> decide(RunTerminalOutcome outcome) {
        return Optional.ofNullable(switch (outcome.status()) {
            case COMPLETED -> switch (outcome.disposition()) {
                case SUBSTANTIVE -> null;
                case CLARIFICATION_ONLY -> UsageCompensationReason.CLARIFICATION_ONLY;
                case UNSUPPORTED_ONLY -> UsageCompensationReason.UNSUPPORTED_ONLY;
            };
            case FAILED -> switch (outcome.failureClass()) {
                case VERIFICATION -> UsageCompensationReason.VERIFICATION_FAILED;
                case BUDGET -> UsageCompensationReason.BUDGET_EXCEEDED;
                case ATTACHMENT -> UsageCompensationReason.INVALID_ATTACHMENT;
                case INFRASTRUCTURE -> UsageCompensationReason.INFRASTRUCTURE_FAILURE;
            };
            case CANCELED -> {
                if (outcome.cancelRequestedFrom() == AiRunStatus.PENDING) {
                    yield UsageCompensationReason.PENDING_CANCELED;
                }
                yield outcome.cancelCause() == RunCancelCause.BUDGET_EXHAUSTED
                        ? UsageCompensationReason.BUDGET_EXCEEDED
                        : null;
            }
            default -> throw new IllegalArgumentException("run is not terminal: " + outcome.status());
        });
    }
}
