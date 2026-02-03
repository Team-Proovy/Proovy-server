package com.proovy.domain.credit.repository;

import com.proovy.domain.credit.entity.CreditChangeType;
import com.proovy.domain.credit.entity.CreditHistory;
import com.proovy.domain.credit.entity.CreditType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface CreditHistoryRepository extends JpaRepository<CreditHistory, Long> {

    // 기본 필터링 쿼리
    @Query("SELECT ch FROM CreditHistory ch " +
            "WHERE ch.user.id = :userId " +
            "AND (:changeType IS NULL OR ch.changeType = :changeType) " +
            "AND (:creditType IS NULL OR ch.creditType = :creditType) " +
            "AND (:startDate IS NULL OR ch.createdAt >= :startDate) " +
            "AND (:endDate IS NULL OR ch.createdAt < :endDate) " +
            "ORDER BY ch.createdAt DESC")
    Page<CreditHistory> findByUserIdWithFilters(
            @Param("userId") Long userId,
            @Param("changeType") CreditChangeType changeType,
            @Param("creditType") CreditType creditType,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            Pageable pageable
    );

    // 기간 통계 쿼리
    @Query("SELECT " +
            "COALESCE(SUM(CASE WHEN ch.changeType = 'EARN' THEN ch.amount ELSE 0 END), 0) as totalEarned, " +
            "COALESCE(SUM(CASE WHEN ch.changeType = 'SPEND' THEN ch.amount ELSE 0 END), 0) as totalSpent, " +
            "COALESCE(SUM(CASE WHEN ch.changeType = 'EXPIRE' THEN ch.amount ELSE 0 END), 0) as totalExpired " +
            "FROM CreditHistory ch " +
            "WHERE ch.user.id = :userId " +
            "AND (:startDate IS NULL OR ch.createdAt >= :startDate) " +
            "AND (:endDate IS NULL OR ch.createdAt < :endDate)")
    CreditPeriodSummary calculatePeriodSummary(
            @Param("userId") Long userId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );

    interface CreditPeriodSummary {
        Long getTotalEarned();
        Long getTotalSpent();
        Long getTotalExpired();
    }
}
