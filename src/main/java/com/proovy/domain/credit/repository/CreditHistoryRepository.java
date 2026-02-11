package com.proovy.domain.credit.repository;

import com.proovy.domain.credit.entity.CreditChangeType;
import com.proovy.domain.credit.entity.CreditHistory;
import com.proovy.domain.credit.entity.CreditType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface CreditHistoryRepository extends JpaRepository<CreditHistory, Long>,
        JpaSpecificationExecutor<CreditHistory> {

    // 기간 통계 쿼리
    @Query("SELECT " +
            "COALESCE(SUM(CASE WHEN ch.changeType = :earn THEN ch.amount ELSE 0 END), 0) as totalEarned, " +
            "COALESCE(SUM(CASE WHEN ch.changeType = :spend THEN ch.amount ELSE 0 END), 0) as totalSpent, " +
            "COALESCE(SUM(CASE WHEN ch.changeType = :expire THEN ch.amount ELSE 0 END), 0) as totalExpired " +
            "FROM CreditHistory ch " +
            "WHERE ch.user.id = :userId " +
            "AND ch.createdAt >= :startDate " +
            "AND ch.createdAt < :endDate")
    CreditPeriodSummary calculatePeriodSummary(
            @Param("userId") Long userId,
            @Param("earn") CreditChangeType earn,
            @Param("spend") CreditChangeType spend,
            @Param("expire") CreditChangeType expire,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );

    @Modifying
    @Query("DELETE FROM CreditHistory ch WHERE ch.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);

    interface CreditPeriodSummary {
        Long getTotalEarned();
        Long getTotalSpent();
        Long getTotalExpired();
    }
}