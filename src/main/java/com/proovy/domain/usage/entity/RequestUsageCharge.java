package com.proovy.domain.usage.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * request_id당 한 번인 최초 일반 사용량 차감 (PK = request_id). 버킷별 차감량을 남겨
 * 보상 시 같은 양을 복구한다. Note 삭제로 run이 사라져도 row와 멱등 키는 남는다.
 */
@Entity
@Table(name = "request_usage_charges")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RequestUsageCharge {

    @Id
    @Column(name = "request_id", length = 128)
    private String requestId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "run_id", updatable = false)
    private UUID runId;

    @Column(name = "usage_units", nullable = false, updatable = false)
    private Integer usageUnits;

    @Column(name = "credit_amount", nullable = false, updatable = false)
    private Integer creditAmount;

    @Column(name = "charged_daily", nullable = false, updatable = false)
    private Integer chargedDaily;

    @Column(name = "charged_free", nullable = false, updatable = false)
    private Integer chargedFree;

    @Column(name = "charged_paid", nullable = false, updatable = false)
    private Integer chargedPaid;

    @Column(name = "charged_at", nullable = false, updatable = false)
    private Instant chargedAt;

    @Builder
    private RequestUsageCharge(String requestId, Long userId, UUID runId, int usageUnits,
                               int chargedDaily, int chargedFree, int chargedPaid, Instant chargedAt) {
        this.requestId = Objects.requireNonNull(requestId);
        this.userId = Objects.requireNonNull(userId);
        this.runId = runId;
        this.usageUnits = usageUnits;
        this.chargedDaily = chargedDaily;
        this.chargedFree = chargedFree;
        this.chargedPaid = chargedPaid;
        this.creditAmount = chargedDaily + chargedFree + chargedPaid;
        this.chargedAt = Objects.requireNonNull(chargedAt);
    }
}
