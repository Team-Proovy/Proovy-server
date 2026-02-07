package com.proovy.domain.credit.service;

import com.proovy.domain.credit.entity.CreditBalance;
import com.proovy.domain.credit.repository.CreditBalanceRepository;
import com.proovy.domain.user.entity.User;
import com.proovy.domain.user.repository.UserRepository;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class CreditBalanceCreator {

    private final CreditBalanceRepository creditBalanceRepository;
    private final UserRepository userRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreditBalance createInitialBalance(Long userId, int freeCredit) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER4041));

        CreditBalance balance = CreditBalance.builder()
                .user(user)
                .dailyFreeCredit(100)
                .dailyFreeLimit(100)
                .dailyExpiresAt(LocalDate.now().plusDays(1).atStartOfDay())
                .freeCredit(freeCredit)
                .paidCredit(0)
                .build();

        return creditBalanceRepository.save(balance);
    }
}
