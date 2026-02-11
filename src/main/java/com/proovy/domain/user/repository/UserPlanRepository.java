package com.proovy.domain.user.repository;

import com.proovy.domain.user.entity.PlanType;
import com.proovy.domain.user.entity.UserPlan;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserPlanRepository extends JpaRepository<UserPlan, Long> {

    @Query("""
            SELECT up FROM UserPlan up
            WHERE up.user.id = :userId
              AND up.isActive = true
            ORDER BY
                CASE WHEN up.startedAt IS NULL THEN 1 ELSE 0 END,
                up.startedAt DESC,
                up.id DESC
            """)
    List<UserPlan> findActiveByUserIdOrderByRecent(@Param("userId") Long userId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT up FROM UserPlan up
            WHERE up.user.id = :userId
              AND up.isActive = true
            ORDER BY
                CASE WHEN up.startedAt IS NULL THEN 1 ELSE 0 END,
                up.startedAt DESC,
                up.id DESC
            """)
    List<UserPlan> findActiveByUserIdForUpdate(@Param("userId") Long userId, Pageable pageable);

    default Optional<UserPlan> findActiveByUserId(Long userId) {
        return findActiveByUserIdOrderByRecent(userId, PageRequest.of(0, 1)).stream().findFirst();
    }

    default Optional<UserPlan> findActiveByUserIdForUpdate(Long userId) {
        return findActiveByUserIdForUpdate(userId, PageRequest.of(0, 1)).stream().findFirst();
    }

    default Optional<PlanType> findActivePlanTypeByUserId(Long userId) {
        return findActiveByUserId(userId)
                .map(UserPlan::getPlanType);
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT up FROM UserPlan up
            WHERE up.user.id = :userId
              AND up.isActive = true
              AND up.canceledAt IS NOT NULL
              AND up.expiredAt IS NOT NULL
              AND up.expiredAt <= :now
            ORDER BY up.expiredAt ASC, up.id ASC
            """)
    List<UserPlan> findDueScheduledChangeByUserIdForUpdate(
            @Param("userId") Long userId,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );

    default Optional<UserPlan> findDueScheduledChangeByUserIdForUpdate(Long userId, LocalDateTime now) {
        return findDueScheduledChangeByUserIdForUpdate(userId, now, PageRequest.of(0, 1)).stream().findFirst();
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT up FROM UserPlan up
            WHERE up.isActive = true
              AND up.canceledAt IS NOT NULL
              AND up.expiredAt IS NOT NULL
              AND up.expiredAt <= :now
            ORDER BY up.expiredAt ASC, up.id ASC
            """)
    List<UserPlan> findDueScheduledChangesForUpdate(@Param("now") LocalDateTime now, Pageable pageable);

    default List<UserPlan> findDueScheduledChangesForUpdate(LocalDateTime now, int batchSize) {
        return findDueScheduledChangesForUpdate(now, PageRequest.of(0, batchSize));
    }

    /**
     * 특정 사용자의 모든 플랜 삭제 (회원 탈퇴용)
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM UserPlan up WHERE up.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
