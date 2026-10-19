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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT cb FROM CreditBalance cb WHERE cb.id = :id")
    Optional<CreditBalance> findByIdForUpdate(@Param("id") Long id);

    /**
     * 호출자 transaction 안에서 잔액 row를 만든다. 이미 있으면(동시 생성 포함) 아무것도 하지 않는다.
     * REQUIRES_NEW로 따로 commit하지 않으므로 호출자 rollback 시 함께 사라진다.
     * 초기값은 {@code CreditBalanceCreator#createInitialBalance}와 같다. credit_balance에 NOT NULL 컬럼을
     * 추가하면 이 SQL도 함께 갱신한다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO credit_balance (user_id, daily_free_credit, daily_free_limit, daily_expires_at, " +
            "free_credit, paid_credit, created_at, updated_at) " +
            "VALUES (:userId, :dailyLimit, :dailyLimit, :dailyExpiresAt, 0, 0, :now, :now) " +
            "ON CONFLICT (user_id) DO NOTHING",
            nativeQuery = true)
    int insertInitialIfAbsent(@Param("userId") Long userId,
                              @Param("dailyLimit") int dailyLimit,
                              @Param("dailyExpiresAt") LocalDateTime dailyExpiresAt,
                              @Param("now") LocalDateTime now);

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
