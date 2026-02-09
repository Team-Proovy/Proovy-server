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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SubscriptionService {

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
        // 1. 사용자 조회
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER4041));

        // 2. 요청한 플랜 타입 검증
        PlanType newPlanType = validateAndGetPlanType(request.planType());

        // 3. 현재 활성 플랜 조회
        UserPlan currentPlan = userPlanRepository.findActiveByUserId(userId)
                .orElseGet(() -> createDefaultFreePlan(user));

        // 4. 플랜 업그레이드 가능 여부 검증
        validateUpgrade(currentPlan.getPlanType(), newPlanType);

        // 5. 기존 플랜 비활성화 (DB에 저장된 경우만)
        if (currentPlan.getId() != null) {
            currentPlan.deactivate();
        }

        // 6. 새로운 플랜 생성
        LocalDateTime now = LocalDateTime.now();
        UserPlan newPlan = UserPlan.builder()
                .user(user)
                .planType(newPlanType)
                .startedAt(now)
                .expiredAt(now.plusMonths(1))
                .isActive(true)
                .build();

        UserPlan savedPlan = userPlanRepository.save(newPlan);

        // 7. 응답 생성
        return SubscriptionResponse.from(savedPlan);
    }

    private PlanType validateAndGetPlanType(String planTypeStr) {
        try {
            PlanType planType = PlanType.valueOf(planTypeStr.toUpperCase());
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
        if (currentPlan.ordinal() > newPlan.ordinal()) {
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
}
