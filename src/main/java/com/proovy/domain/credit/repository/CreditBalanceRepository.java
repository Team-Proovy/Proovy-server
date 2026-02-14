package com.proovy.domain.credit.repository;

import com.proovy.domain.credit.entity.CreditBalance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CreditBalanceRepository extends JpaRepository<CreditBalance, Long> {

    @Query("SELECT cb FROM CreditBalance cb WHERE cb.user.id = :userId")
    Optional<CreditBalance> findByUserId(@Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT cb FROM CreditBalance cb WHERE cb.user.id = :userId")
    Optional<CreditBalance> findByUserIdForUpdate(@Param("userId") Long userId);

    @Modifying
    @Query("DELETE FROM CreditBalance cb WHERE cb.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);

    /**
     * 일일 크레딧이 만료된 사용자 ID 목록 조회 (배치 처리용)
     */
    @Query(value = "SELECT id FROM credit_balance " +
            "WHERE daily_expires_at IS NOT NULL " +
            "AND daily_expires_at <= :now " +
            "ORDER BY id " +
            "LIMIT :limit",
            nativeQuery = true)
    List<Long> findExpiredDailyCreditIds(@Param("now") LocalDateTime now,
                                          @Param("limit") int limit);
}
