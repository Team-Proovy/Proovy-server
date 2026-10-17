package com.proovy.domain.credit.service;

import com.proovy.domain.credit.entity.CreditBalance;
import com.proovy.domain.credit.repository.CreditBalanceRepository;
import com.proovy.domain.user.entity.PlanType;
import com.proovy.domain.user.entity.User;
import com.proovy.domain.user.repository.UserPlanRepository;
import com.proovy.domain.user.repository.UserRepository;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Service
@RequiredArgsConstructor
public class CreditBalanceCreator {
    private static final ZoneId BILLING_ZONE = ZoneId.of("Asia/Seoul");

    private final CreditBalanceRepository creditBalanceRepository;
    private final UserRepository userRepository;
    private final UserPlanRepository userPlanRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreditBalance createInitialBalance(Long userId, int freeCredit) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER4041));

        // 현재 플랜의 dailyCreditLimit 조회
        PlanType planType = userPlanRepository.findActivePlanTypeByUserId(userId)
                .orElse(PlanType.FREE);
        int dailyCreditLimit = planType.getDailyCreditLimit();

        CreditBalance balance = CreditBalance.builder()
                .user(user)
                .dailyFreeCredit(dailyCreditLimit)
                .dailyFreeLimit(dailyCreditLimit)
                .dailyExpiresAt(nextDailyResetAt(nowInBillingZone()))
                .freeCredit(freeCredit)
                .paidCredit(0)
                .build();

        return creditBalanceRepository.save(balance);
    }

    /**
     * 호출자 transaction 안에서 초기 잔액 row를 만든다(가입 보너스 없음). 이미 있으면 그대로 둔다.
     * 요청 사용량 차감처럼 독립 선commit이 허용되지 않는 경로에서 쓴다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void insertInitialBalanceIfAbsent(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new BusinessException(ErrorCode.USER4041);
        }
        PlanType planType = userPlanRepository.findActivePlanTypeByUserId(userId)
                .orElse(PlanType.FREE);
        LocalDateTime now = nowInBillingZone();
        creditBalanceRepository.insertInitialIfAbsent(
                userId, planType.getDailyCreditLimit(), nextDailyResetAt(now), now);
    }

    private LocalDateTime nowInBillingZone() {
        return ZonedDateTime.now(BILLING_ZONE).toLocalDateTime();
    }

    private LocalDateTime nextDailyResetAt(LocalDateTime now) {
        return now.toLocalDate().plusDays(1).atStartOfDay();
    }
}
