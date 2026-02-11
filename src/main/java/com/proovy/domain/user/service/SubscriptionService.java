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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SubscriptionService {
    private static final ZoneId BILLING_ZONE = ZoneId.of("Asia/Seoul");

    private final UserRepository userRepository;
    private final UserPlanRepository userPlanRepository;

    public SubscriptionResponse getSubscription(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER4041));

        UserPlan userPlan = userPlanRepository.findActiveByUserId(userId)
                .orElseGet(() -> createDefaultFreePlan(user));

        return SubscriptionResponse.from(userPlan);
    }

    @Transactional
    public SubscriptionResponse upgradePlan(Long userId, UpgradePlanRequest request) {
        // 1. 사용자 행을 선점해 동시 업그레이드 요청을 직렬화
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER4041));

        // 2. 요청한 플랜 타입 검증
        PlanType newPlanType = validateAndGetPlanType(request.planType());

        // 3. 현재 활성 플랜 조회 (비관적 잠금)
        UserPlan currentPlan = userPlanRepository.findActiveByUserIdForUpdate(userId)
                .orElseGet(() -> createDefaultFreePlan(user));

        // 4. 플랜 업그레이드 가능 여부 검증
        validateUpgrade(currentPlan.getPlanType(), newPlanType);

        // 5. 기존 플랜 비활성화 (DB에 저장된 경우만)
        if (currentPlan.getId() != null) {
            currentPlan.deactivate();
        }

        // 6. 새로운 플랜 생성
        ZonedDateTime now = ZonedDateTime.now(BILLING_ZONE);
        UserPlan newPlan = UserPlan.builder()
                .user(user)
                .planType(newPlanType)
                .startedAt(now.toLocalDateTime())
                .expiredAt(now.plusMonths(1).toLocalDateTime())
                .isActive(true)
                .build();

        UserPlan savedPlan;
        try {
            savedPlan = userPlanRepository.save(newPlan);
        } catch (DataIntegrityViolationException e) {
            log.warn("동시 업그레이드 충돌 감지: userId={}, requestedPlan={}", userId, newPlanType, e);
            throw new BusinessException(ErrorCode.USER4092);
        }

        // 7. 응답 생성
        return SubscriptionResponse.from(savedPlan);
    }

    private PlanType validateAndGetPlanType(String planTypeStr) {
        if (planTypeStr == null || planTypeStr.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.USER4001);
        }
        try {
            PlanType planType = PlanType.valueOf(planTypeStr.trim().toUpperCase(Locale.ROOT));
            if (planType == PlanType.FREE) {
                throw new BusinessException(ErrorCode.USER4001);
            }
            return planType;
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.USER4001);
        }
    }

    private void validateUpgrade(PlanType currentPlan, PlanType newPlan) {
        // 동일한 플랜으로 업그레이드 시도
        if (currentPlan == newPlan) {
            throw new BusinessException(ErrorCode.USER4002);
        }

        // 다운그레이드 시도
        if (currentPlan.getLevel() > newPlan.getLevel()) {
            throw new BusinessException(ErrorCode.USER4003);
        }
    }

    private UserPlan createDefaultFreePlan(User user) {
        return UserPlan.builder()
                .user(user)
                .planType(PlanType.FREE)
                .isActive(true)
                .build();
    }

    @Transactional
    public SubscriptionResponse cancelSubscription(Long userId) {
        // 1. 현재 활성 구독 조회
        UserPlan activePlan = userPlanRepository.findActiveByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER4042));

        // 2. FREE 플랜은 취소 불가
        if (activePlan.getPlanType() == PlanType.FREE) {
            throw new BusinessException(ErrorCode.USER4004);
        }

        // 3. 이미 취소된 구독인지 확인
        if (activePlan.getCanceledAt() != null) {
            throw new BusinessException(ErrorCode.USER4005);
        }

        // 4. 구독 취소 처리 (자동갱신만 비활성화, 만료일까지 혜택 유지)
        LocalDateTime now = LocalDateTime.now();
        activePlan.cancel(now);

        // 5. 응답 생성
        return SubscriptionResponse.fromCanceled(activePlan, now);
    }
}
