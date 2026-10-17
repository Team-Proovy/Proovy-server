package com.proovy.domain.usage.entity;

import com.proovy.global.entity.AssignedIdEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

/**
 * request_id당 최대 한 번인 Usage Compensation (PK = request_id). 차감 row가 있어야 하고
 * 복구량은 차감량과 같다(composite FK). 경합하는 보상 사유는 PK 충돌로 하나만 남는다.
 */
@Entity
@Table(name = "request_usage_compensations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RequestUsageCompensation extends AssignedIdEntity<String> {

    @Id
    @Column(name = "request_id", length = 128)
    private String requestId;

    @Column(name = "credit_amount", nullable = false, updatable = false)
    private Integer creditAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40, updatable = false)
    private UsageCompensationReason reason;

    @Column(name = "restored_daily", nullable = false, updatable = false)
    private Integer restoredDaily;

    @Column(name = "restored_free", nullable = false, updatable = false)
    private Integer restoredFree;

    @Column(name = "restored_paid", nullable = false, updatable = false)
    private Integer restoredPaid;

    @Column(name = "compensated_at", nullable = false, updatable = false)
    private Instant compensatedAt;

    @Builder
    private RequestUsageCompensation(String requestId, UsageCompensationReason reason,
                                     int restoredDaily, int restoredFree, int restoredPaid,
                                     Instant compensatedAt) {
        this.requestId = Objects.requireNonNull(requestId);
        this.reason = Objects.requireNonNull(reason);
        this.restoredDaily = restoredDaily;
        this.restoredFree = restoredFree;
        this.restoredPaid = restoredPaid;
        this.creditAmount = restoredDaily + restoredFree + restoredPaid;
        this.compensatedAt = Objects.requireNonNull(compensatedAt);
    }

    @Override
    public String getId() {
        return requestId;
    }
}
