package com.proovy.domain.credit.service;

import com.proovy.domain.credit.entity.*;
import com.proovy.domain.credit.repository.CreditBalanceRepository;
import com.proovy.domain.credit.repository.CreditHistoryRepository;
import com.proovy.domain.user.entity.PlanType;
import com.proovy.domain.user.entity.User;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreditBalanceService {

    private final CreditBalanceRepository creditBalanceRepository;
    private final CreditBalanceCreator creditBalanceCreator;
    private final CreditHistoryRepository creditHistoryRepository;

    @Transactional
    public CreditBalance getOrCreateBalance(Long userId) {
        return creditBalanceRepository.findByUserId(userId)
                .orElseGet(() -> {
                    // 가입 보너스는 signup 경로에서만 지급됩니다.
                    createInitialBalanceSafely(userId, 0);
                    return creditBalanceRepository.findByUserId(userId)
                            .orElseThrow(() -> new BusinessException(ErrorCode.CREDIT4041));
                });
    }

    @Transactional
    public CreditBalance getOrCreateBalanceForUpdate(Long userId) {
        return creditBalanceRepository.findByUserIdForUpdate(userId)
                .orElseGet(() -> {
                    createInitialBalanceSafely(userId, 0);
                    return creditBalanceRepository.findByUserIdForUpdate(userId)
                            .orElseThrow(() -> new BusinessException(ErrorCode.CREDIT4041));
                });
    }

    @Transactional
    public CreditBalance createSignupBalance(User user) {
        // FREE 플랜의 dailyCreditLimit 사용
        int dailyCreditLimit = PlanType.FREE.getDailyCreditLimit();
        int signupBonus = 100;

        CreditBalance balance = CreditBalance.builder()
                .user(user)
                .dailyFreeCredit(dailyCreditLimit)
                .dailyFreeLimit(dailyCreditLimit)
                .dailyExpiresAt(LocalDate.now().plusDays(1).atStartOfDay())
                .freeCredit(signupBonus)
                .paidCredit(0)
                .build();
        CreditBalance savedBalance = creditBalanceRepository.save(balance);

        // 가입 보너스 히스토리 기록
        CreditHistory history = CreditHistory.builder()
                .user(user)
                .eventType(CreditEventType.SIGNUP_BONUS)
                .eventName("가입 보너스")
                .description("회원가입 축하 크레딧")
                .amount(signupBonus)
                .changeType(CreditChangeType.EARN)
                .creditType(CreditType.FREE)
                .balanceAfterDaily(savedBalance.getDailyFreeCredit())
                .balanceAfterFree(savedBalance.getFreeCredit())
                .balanceAfterPaid(savedBalance.getPaidCredit())
                .build();
        creditHistoryRepository.save(history);

        return savedBalance;
    }

    /**
     * 월간 구독 크레딧 부여 (플랜 변경/갱신 시 호출)
     */
    @Transactional
    public void grantMonthlyCredit(Long userId, PlanType planType) {
        if (planType == PlanType.FREE) {
            return; // FREE 플랜은 월간 크레딧 없음
        }

        CreditBalance balance = getOrCreateBalanceForUpdate(userId);
        int monthlyCredit = planType.getMonthlyCreditLimit();
        int dailyCreditLimit = planType.getDailyCreditLimit();

        // 1. Monthly Credit 부여
        balance.addPaidCredit(monthlyCredit);

        // 2. Daily Limit 업데이트
        if (balance.getDailyFreeLimit() != dailyCreditLimit) {
            log.info("플랜 변경으로 일일 크레딧 한도 변경: userId={}, {} -> {}",
                    userId, balance.getDailyFreeLimit(), dailyCreditLimit);
            balance.updateDailyLimit(dailyCreditLimit);
            balance.resetDailyCredit(LocalDate.now().plusDays(1).atStartOfDay());
        }

        // 3. Paid Credit 만료일 설정 (1개월 후)
        LocalDateTime expiresAt = LocalDateTime.now().plusMonths(1);
        balance.setPaidExpiresAt(expiresAt);

        creditBalanceRepository.save(balance);

        // 4. 히스토리 기록
        CreditHistory history = CreditHistory.builder()
                .user(balance.getUser())
                .eventType(CreditEventType.MONTHLY_GRANT)
                .eventName(planType.getDisplayName() + " 플랜 구독 크레딧")
                .description(planType.getDisplayName() + " 플랜 월간 크레딧 " + monthlyCredit + " 지급")
                .amount(monthlyCredit)
                .changeType(CreditChangeType.EARN)
                .creditType(CreditType.PAID)
                .balanceAfterDaily(balance.getDailyFreeCredit())
                .balanceAfterFree(balance.getFreeCredit())
                .balanceAfterPaid(balance.getPaidCredit())
                .build();
        creditHistoryRepository.save(history);

        log.info("월간 크레딧 부여 완료: userId={}, planType={}, amount={}", userId, planType, monthlyCredit);
    }

    private void createInitialBalanceSafely(Long userId, int freeCredit) {
        try {
            creditBalanceCreator.createInitialBalance(userId, freeCredit);
        } catch (DataIntegrityViolationException e) {
            log.warn("크레딧 잔액 동시 생성 감지, userId={}", userId);
        }
    }
}
