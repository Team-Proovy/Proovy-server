package com.proovy.domain.user.repository;

import com.proovy.domain.user.entity.PlanType;
import com.proovy.domain.user.entity.UserPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserPlanRepository extends JpaRepository<UserPlan, Long> {

    @Query("SELECT up FROM UserPlan up WHERE up.user.id = :userId AND up.isActive = true ORDER BY up.startedAt DESC LIMIT 1")
    Optional<UserPlan> findActiveByUserId(@Param("userId") Long userId);

    @Query("SELECT COALESCE(up.planType, 'FREE') FROM UserPlan up WHERE up.user.id = :userId AND up.isActive = true ORDER BY up.startedAt DESC LIMIT 1")
    Optional<PlanType> findActivePlanTypeByUserId(@Param("userId") Long userId);
}
