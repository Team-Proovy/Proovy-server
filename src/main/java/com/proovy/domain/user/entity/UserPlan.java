package com.proovy.domain.user.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_plans")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_plan_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_type", nullable = false, length = 10)
    private PlanType planType = PlanType.FREE;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "expired_at")
    private LocalDateTime expiredAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "next_plan_type", length = 10)
    private PlanType nextPlanType;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Builder
    public UserPlan(User user, PlanType planType, LocalDateTime startedAt, LocalDateTime expiredAt, LocalDateTime canceledAt, PlanType nextPlanType, Boolean isActive) {
        this.user = user;
        this.planType = planType != null ? planType : PlanType.FREE;
        this.startedAt = startedAt;
        this.expiredAt = expiredAt;
        this.canceledAt = canceledAt;
        this.nextPlanType = nextPlanType;
        this.isActive = isActive != null ? isActive : true;
    }

    public double getStorageLimitGb() {
        return planType.getStorageLimitGb();
    }

    public long getStorageLimitBytes() {
        return planType.getStorageLimitBytes();
    }

    public int getStorageLimitMb() {
        return planType.getStorageLimitMb();
    }

    public void deactivate() {
        this.isActive = false;
    }

    public void schedulePlanChange(PlanType nextPlanType, LocalDateTime canceledAt) {
        this.canceledAt = canceledAt;
        this.nextPlanType = nextPlanType;
        // isActive는 그대로 유지 - 만료일까지 혜택 제공
    }

    public void resumeAutoRenew() {
        this.canceledAt = null;
        this.nextPlanType = null;
    }
}
