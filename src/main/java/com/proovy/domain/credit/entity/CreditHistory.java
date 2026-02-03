package com.proovy.domain.credit.entity;

import com.proovy.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "credit_history", indexes = {
        @Index(name = "idx_credit_history_user_created", columnList = "user_id, created_at"),
        @Index(name = "idx_credit_history_change_type", columnList = "change_type"),
        @Index(name = "idx_credit_history_credit_type", columnList = "credit_type")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
public class CreditHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "history_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private CreditEventType eventType;

    @Column(name = "event_name", nullable = false, length = 100)
    private String eventName;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "amount", nullable = false)
    private Integer amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 10)
    private CreditChangeType changeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "credit_type", nullable = false, length = 10)
    private CreditType creditType;

    // 변동 후 잔액 스냅샷
    @Column(name = "balance_after_daily", nullable = false)
    private Integer balanceAfterDaily;

    @Column(name = "balance_after_free", nullable = false)
    private Integer balanceAfterFree;

    @Column(name = "balance_after_paid", nullable = false)
    private Integer balanceAfterPaid;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    protected CreditHistory() {}

    @Builder
    public CreditHistory(User user, CreditEventType eventType, String eventName,
                         String description, Integer amount, CreditChangeType changeType,
                         CreditType creditType, Integer balanceAfterDaily,
                         Integer balanceAfterFree, Integer balanceAfterPaid) {
        this.user = user;
        this.eventType = eventType;
        this.eventName = eventName;
        this.description = description;
        this.amount = amount;
        this.changeType = changeType;
        this.creditType = creditType;
        this.balanceAfterDaily = balanceAfterDaily;
        this.balanceAfterFree = balanceAfterFree;
        this.balanceAfterPaid = balanceAfterPaid;
    }
}
