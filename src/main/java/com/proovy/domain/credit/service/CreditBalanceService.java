package com.proovy.domain.credit.service;

import com.proovy.domain.credit.entity.CreditBalance;
import com.proovy.domain.credit.repository.CreditBalanceRepository;
import com.proovy.domain.user.entity.User;
import com.proovy.domain.user.repository.UserRepository;
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
    private final UserRepository userRepository;

    @Transactional
    public CreditBalance getOrCreateBalance(Long userId) {
        return creditBalanceRepository.findByUserId(userId)
                .orElseGet(() -> createInitialBalance(userId, false));
    }

    @Transactional
    public CreditBalance getOrCreateBalanceForUpdate(Long userId) {
        return creditBalanceRepository.findByUserIdForUpdate(userId)
                .orElseGet(() -> createInitialBalance(userId, true));
    }

    private CreditBalance createInitialBalance(Long userId, boolean forUpdate) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER4041));

        CreditBalance balance = CreditBalance.builder()
                .user(user)
                .dailyFreeCredit(100)
                .dailyFreeLimit(100)
                .dailyExpiresAt(LocalDate.now().plusDays(1).atStartOfDay())
                .freeCredit(0)
                .paidCredit(0)
                .build();

        try {
            CreditBalance saved = creditBalanceRepository.save(balance);
            return forUpdate
                    ? creditBalanceRepository.findByUserIdForUpdate(userId).orElse(saved)
                    : saved;
        } catch (DataIntegrityViolationException e) {
            log.warn("크레딧 잔액 동시 생성 감지, userId={}", userId);
            return forUpdate
                    ? creditBalanceRepository.findByUserIdForUpdate(userId)
                            .orElseThrow(() -> new BusinessException(ErrorCode.CREDIT4041))
                    : creditBalanceRepository.findByUserId(userId)
                            .orElseThrow(() -> new BusinessException(ErrorCode.CREDIT4041));
        }
    }
}
