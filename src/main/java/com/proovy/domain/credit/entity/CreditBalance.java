package com.proovy.domain.credit.entity;

import com.proovy.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "credit_balance")
@EntityListeners(AuditingEntityListener.class)
@Getter
public class CreditBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "balance_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "daily_free_credit", nullable = false)
    private Integer dailyFreeCredit = 0;

    @Column(name = "daily_free_limit", nullable = false)
    private Integer dailyFreeLimit = 100;

    @Column(name = "daily_expires_at")
    private LocalDateTime dailyExpiresAt;

    @Column(name = "free_credit", nullable = false)
    private Integer freeCredit = 0;

    @Column(name = "paid_credit", nullable = false)
    private Integer paidCredit = 0;

    @Column(name = "paid_expires_at")
    private LocalDateTime paidExpiresAt;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    protected CreditBalance() {}

    @Builder
    public CreditBalance(User user, Integer dailyFreeCredit, Integer dailyFreeLimit,
                         LocalDateTime dailyExpiresAt, Integer freeCredit,
                         Integer paidCredit, LocalDateTime paidExpiresAt) {
        this.user = user;
        this.dailyFreeCredit = dailyFreeCredit != null ? dailyFreeCredit : 0;
        this.dailyFreeLimit = dailyFreeLimit != null ? dailyFreeLimit : 100;
        this.dailyExpiresAt = dailyExpiresAt;
        this.freeCredit = freeCredit != null ? freeCredit : 0;
        this.paidCredit = paidCredit != null ? paidCredit : 0;
        this.paidExpiresAt = paidExpiresAt;
    }

    public Integer getTotalAvailable() {
        return dailyFreeCredit + freeCredit + paidCredit;
    }

    /**
     * 크레딧을 차감합니다. (우선순위: 일일 무료 → 무료 → 유료)
     * <p>
     * 요청 금액이 총 가용 크레딧보다 큰 경우, 예외 없이 가능한 만큼만 차감됩니다.
     * 호출자는 차감 전후 잔액을 비교하여 전액 차감 여부를 확인할 수 있습니다.
     * </p>
     *
     * @param amount 차감할 크레딧 양 (양수)
     */
    public void deductCredit(int amount) {
        if (amount <= 0) return;

        int remaining = amount;

        // 1. 일일 무료 크레딧 차감
        if (dailyFreeCredit > 0) {
            int deduct = Math.min(dailyFreeCredit, remaining);
            dailyFreeCredit -= deduct;
            remaining -= deduct;
        }

        // 2. 무료 크레딧 차감
        if (remaining > 0 && freeCredit > 0) {
            int deduct = Math.min(freeCredit, remaining);
            freeCredit -= deduct;
            remaining -= deduct;
        }

        // 3. 유료 크레딧 차감
        if (remaining > 0 && paidCredit > 0) {
            int deduct = Math.min(paidCredit, remaining);
            paidCredit -= deduct;
        }
    }

    // 크레딧 추가
    public void addDailyCredit(int amount) {
        this.dailyFreeCredit += amount;
    }

    public void addFreeCredit(int amount) {
        this.freeCredit += amount;
    }

    public void addPaidCredit(int amount) {
        this.paidCredit += amount;
    }

    // 일일 크레딧 리셋
    public void resetDailyCredit(LocalDateTime nextExpiry) {
        this.dailyFreeCredit = this.dailyFreeLimit;
        this.dailyExpiresAt = nextExpiry;
    }

    // 만료 처리
    public void expirePaidCredit() {
        this.paidCredit = 0;
        this.paidExpiresAt = null;
    }

    public void expireDailyCredit() {
        this.dailyFreeCredit = 0;
    }
}
