package com.proovy.domain.user.service;

import com.proovy.domain.asset.repository.AssetRepository;
import com.proovy.domain.user.dto.response.MyProfileResponse;
import com.proovy.domain.user.dto.response.MyProfileResponse.*;
import com.proovy.domain.user.entity.PlanType;
import com.proovy.domain.user.entity.User;
import com.proovy.domain.user.repository.UserPlanRepository;
import com.proovy.domain.user.repository.UserRepository;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final UserPlanRepository userPlanRepository;
    private final AssetRepository assetRepository;

    /**
     * 내 프로필 조회
     */
    public MyProfileResponse getMyProfile(Long userId) {
        // 1. 사용자 조회
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER4041));

        // 2. 구독 정보 조회
        SubscriptionDto subscription = getSubscription(userId);

        // 3. 크레딧 정보 조회 (TODO: Credit 도메인 구현 후 연동)
        CreditDto credit = getCreditInfo(userId);

        // 4. 스토리지 정보 조회
        StorageDto storage = getStorageInfo(userId);

        return MyProfileResponse.from(user, subscription, credit, storage);
    }

    private SubscriptionDto getSubscription(Long userId) {
        return userPlanRepository.findActiveByUserId(userId)
                .map(plan -> SubscriptionDto.builder()
                        .plan(plan.getPlanType().getDisplayName())
                        .startDate(formatDate(plan.getStartedAt()))
                        .endDate(formatDate(plan.getExpiredAt()))
                        .build())
                .orElse(SubscriptionDto.builder()
                        .plan(PlanType.FREE.getDisplayName())
                        .startDate(null)
                        .endDate(null)
                        .build());
    }

    private CreditDto getCreditInfo(Long userId) {
        // TODO: Credit 도메인 구현 후 실제 데이터로 교체
        PlanType planType = userPlanRepository.findActivePlanTypeByUserId(userId)
                .orElse(PlanType.FREE);
        LocalDateTime tomorrow = LocalDate.now().plusDays(1).atStartOfDay();

        DailyCreditDto dailyCredit = DailyCreditDto.builder()
                .balance(100)
                .limit(100)
                .resetsAt(tomorrow.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .build();

        MonthlyCreditDto monthlyCredit = MonthlyCreditDto.builder()
                .balance(planType.getMonthlyCreditLimit())
                .limit(planType.getMonthlyCreditLimit())
                .expiresAt(null)
                .build();

        return CreditDto.builder()
                .dailyCredit(dailyCredit)
                .monthlyCredit(monthlyCredit)
                .totalAvailable(dailyCredit.balance() + monthlyCredit.balance())
                .build();
    }

    private StorageDto getStorageInfo(Long userId) {
        Long usedBytes = assetRepository.sumFileSizeByUserId(userId);
        double usedGb = usedBytes != null ? usedBytes / (1024.0 * 1024.0 * 1024.0) : 0.0;

        PlanType planType = userPlanRepository.findActivePlanTypeByUserId(userId)
                .orElse(PlanType.FREE);

        double limitGb = planType.getStorageLimitGb();

        return StorageDto.builder()
                .used(round2(usedGb))  // 소수 2자리
                .limit(limitGb)
                .unit("GB")
                .build();
    }

    private double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private String formatDate(LocalDateTime dateTime) {
        if (dateTime == null) return null;
        return dateTime.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }
}
