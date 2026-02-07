package com.proovy.domain.credit.service;

import com.proovy.domain.credit.entity.CreditBalance;
import com.proovy.domain.credit.repository.CreditBalanceRepository;
import com.proovy.domain.user.entity.User;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreditBalanceService {

    private final CreditBalanceRepository creditBalanceRepository;
    private final CreditBalanceCreator creditBalanceCreator;

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
        CreditBalance balance = CreditBalance.builder()
                .user(user)
                .dailyFreeCredit(100)
                .dailyFreeLimit(100)
                .dailyExpiresAt(LocalDate.now().plusDays(1).atStartOfDay())
                .freeCredit(100)
                .paidCredit(0)
                .build();
        return creditBalanceRepository.save(balance);
    }

    private void createInitialBalanceSafely(Long userId, int freeCredit) {
        try {
            creditBalanceCreator.createInitialBalance(userId, freeCredit);
        } catch (DataIntegrityViolationException e) {
            log.warn("크레딧 잔액 동시 생성 감지, userId={}", userId);
        }
    }
}
