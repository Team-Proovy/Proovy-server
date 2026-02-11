package com.proovy.domain.user.service;

import com.proovy.domain.user.dto.request.UpgradePlanRequest;
import com.proovy.domain.user.dto.response.SubscriptionResponse;
import com.proovy.domain.user.entity.PlanType;
import com.proovy.domain.user.entity.User;
import com.proovy.domain.user.entity.UserPlan;
import com.proovy.domain.user.repository.UserPlanRepository;
import com.proovy.domain.user.repository.UserRepository;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SubscriptionService {
    private static final ZoneId BILLING_ZONE = ZoneId.of("Asia/Seoul");
    private static final int PLAN_TRANSITION_BATCH_SIZE = 200;

    private final UserRepository userRepository;
    private final UserPlanRepository userPlanRepository;
    private final PlatformTransactionManager transactionManager;

    @Transactional
    public SubscriptionResponse getSubscription(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER4041));

        applyDuePlanChangeForUser(userId);

        UserPlan userPlan = userPlanRepository.findActiveByUserId(userId)
                .orElseGet(() -> createDefaultFreePlan(user));

        return SubscriptionResponse.from(userPlan);
    }

    @Transactional
    public SubscriptionResponse upgradePlan(Long userId, UpgradePlanRequest request) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER4041));

        applyDuePlanChangeForUser(userId);

        PlanType requestedPlanType = validateAndGetPlanType(request.planType());

        UserPlan currentPlan = userPlanRepository.findActiveByUserIdForUpdate(userId)
                .orElseGet(() -> createDefaultFreePlan(user));

        if (requestedPlanType == currentPlan.getPlanType()) {
            if (requestedPlanType == PlanType.FREE) {
                throw new BusinessException(ErrorCode.USER4004);
            }
            if (currentPlan.getCanceledAt() != null) {
                currentPlan.resumeAutoRenew();
                return SubscriptionResponse.from(currentPlan);
            }
            throw new BusinessException(ErrorCode.USER4002);
        }

        if (requestedPlanType.getLevel() > currentPlan.getPlanType().getLevel()) {
            return changePlanImmediately(user, currentPlan, requestedPlanType);
        }

        return schedulePlanChange(currentPlan, requestedPlanType);
    }

    @Transactional
    public SubscriptionResponse cancelSubscription(Long userId) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER4041));

        applyDuePlanChangeForUser(userId);

        UserPlan activePlan = userPlanRepository.findActiveByUserIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER4042));

        return schedulePlanChange(activePlan, PlanType.FREE);
    }

    @Scheduled(fixedDelayString = "${proovy.subscription.plan-transition-fixed-delay-ms:60000}")
    @Transactional(readOnly = true)
    public void processDuePlanTransitions() {
        LocalDateTime now = nowInBillingZone();
        List<Long> duePlanIds = userPlanRepository.findDueScheduledChangeIds(now, PLAN_TRANSITION_BATCH_SIZE);
        int processedCount = 0;

        for (Long duePlanId : duePlanIds) {
            if (processDuePlanTransitionInNewTransaction(duePlanId, now)) {
                processedCount++;
            }
        }

        if (!duePlanIds.isEmpty()) {
            log.info("예약된 플랜 변경 처리 완료: requestedCount={}, processedCount={}", duePlanIds.size(), processedCount);
        }
    }

    private PlanType validateAndGetPlanType(String planTypeStr) {
        if (planTypeStr == null || planTypeStr.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.USER4001);
        }
        try {
            return PlanType.valueOf(planTypeStr.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.USER4001);
        }
    }

    private SubscriptionResponse changePlanImmediately(User user, UserPlan currentPlan, PlanType targetPlan) {
        if (currentPlan.getId() != null) {
            currentPlan.deactivate();
            userPlanRepository.flush();
        }

        LocalDateTime startedAt = nowInBillingZone();
        UserPlan newPlan = buildNextActivePlan(user, targetPlan, startedAt);

        try {
            return SubscriptionResponse.from(userPlanRepository.save(newPlan));
        } catch (DataIntegrityViolationException e) {
            log.warn("동시 플랜 변경 충돌 감지: userId={}, requestedPlan={}", user.getId(), targetPlan, e);
            throw new BusinessException(ErrorCode.USER4092);
        }
    }

    private SubscriptionResponse schedulePlanChange(UserPlan activePlan, PlanType targetPlan) {
        if (activePlan.getPlanType() == PlanType.FREE) {
            throw new BusinessException(ErrorCode.USER4004);
        }

        PlanType currentScheduledNextPlan = resolveNextPlanType(activePlan);
        if (activePlan.getCanceledAt() != null && currentScheduledNextPlan == targetPlan) {
            throw new BusinessException(ErrorCode.USER4005);
        }

        if (activePlan.getExpiredAt() == null) {
            return changePlanImmediately(activePlan.getUser(), activePlan, targetPlan);
        }

        LocalDateTime canceledAt = nowInBillingZone();
        activePlan.schedulePlanChange(targetPlan, canceledAt);
        return SubscriptionResponse.fromCanceled(activePlan, canceledAt);
    }

    private void applyDuePlanChangeForUser(Long userId) {
        LocalDateTime now = nowInBillingZone();
        try {
            userPlanRepository.findDueScheduledChangeByUserIdForUpdate(userId, now)
                    .ifPresent(this::applyDuePlanChange);
        } catch (DataIntegrityViolationException e) {
            log.warn("사용자별 만료 플랜 전환 충돌 감지: userId={}", userId, e);
            throw new BusinessException(ErrorCode.USER4092);
        }
    }

    private void applyDuePlanChange(UserPlan duePlan) {
        if (duePlan.getId() == null) {
            return;
        }

        PlanType nextPlanType = resolveNextPlanType(duePlan);
        LocalDateTime transitionAt = duePlan.getExpiredAt() != null ? duePlan.getExpiredAt() : nowInBillingZone();

        duePlan.deactivate();
        userPlanRepository.flush();

        UserPlan nextPlan = buildNextActivePlan(duePlan.getUser(), nextPlanType, transitionAt);
        userPlanRepository.save(nextPlan);
    }

    private boolean processDuePlanTransitionInNewTransaction(Long duePlanId, LocalDateTime now) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        try {
            return Boolean.TRUE.equals(transactionTemplate.execute(status ->
                    userPlanRepository.findByIdWithLock(duePlanId)
                            .filter(plan -> isDueScheduledChange(plan, now))
                            .map(plan -> {
                                applyDuePlanChange(plan);
                                return true;
                            })
                            .orElse(false)
            ));
        } catch (DataIntegrityViolationException e) {
            log.warn("예약 플랜 전환 충돌 감지: userPlanId={}", duePlanId, e);
            return false;
        } catch (RuntimeException e) {
            log.warn("예약 플랜 전환 처리 실패: userPlanId={}", duePlanId, e);
            return false;
        }
    }

    private boolean isDueScheduledChange(UserPlan plan, LocalDateTime now) {
        return Boolean.TRUE.equals(plan.getIsActive())
                && plan.getCanceledAt() != null
                && plan.getExpiredAt() != null
                && !plan.getExpiredAt().isAfter(now);
    }

    private UserPlan buildNextActivePlan(User user, PlanType planType, LocalDateTime startedAt) {
        if (planType == PlanType.FREE) {
            return UserPlan.builder()
                    .user(user)
                    .planType(PlanType.FREE)
                    .isActive(true)
                    .build();
        }

        return UserPlan.builder()
                .user(user)
                .planType(planType)
                .startedAt(startedAt)
                .expiredAt(startedAt.plusMonths(1))
                .isActive(true)
                .build();
    }

    private PlanType resolveNextPlanType(UserPlan userPlan) {
        return userPlan.getNextPlanType() != null ? userPlan.getNextPlanType() : PlanType.FREE;
    }

    private UserPlan createDefaultFreePlan(User user) {
        return UserPlan.builder()
                .user(user)
                .planType(PlanType.FREE)
                .isActive(true)
                .build();
    }

    private LocalDateTime nowInBillingZone() {
        return ZonedDateTime.now(BILLING_ZONE).toLocalDateTime();
    }
}
