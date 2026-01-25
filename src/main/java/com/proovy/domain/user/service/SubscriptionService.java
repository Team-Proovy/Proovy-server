package com.proovy.domain.user.service;

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

    private UserPlan createDefaultFreePlan(User user) {
        return UserPlan.builder()
                .user(user)
                .planType(PlanType.FREE)
                .isActive(true)
                .build();
    }
}
