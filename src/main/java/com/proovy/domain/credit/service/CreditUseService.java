package com.proovy.domain.credit.service;

import com.proovy.domain.credit.dto.response.CreditBalanceResponse;
import com.proovy.domain.credit.entity.CreditBalance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 브라우저용 잔액 조회. 구형 기능별 비용표·브라우저 임의 차감({@code POST /api/credits/use})은 제거했다.
 * 일반 AI 요청의 차감·보상은 {@code com.proovy.domain.usage.service.RequestUsageService}만 수행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditUseService {

    private final CreditBalanceService creditBalanceService;

    /**
     * 크레딧 잔액 조회
     */
    @Transactional
    public CreditBalanceResponse getBalance(Long userId, Integer checkCost) {
        CreditBalance balance = creditBalanceService.getOrCreateBalance(userId);

        Integer totalAvailable = balance.getTotalAvailable();
        Boolean canUse = checkCost == null || totalAvailable >= checkCost;

        return CreditBalanceResponse.builder()
                .dailyFreeCredit(balance.getDailyFreeCredit())
                .dailyFreeLimit(balance.getDailyFreeLimit())
                .dailyExpiresAt(balance.getDailyExpiresAt())
                .freeCredit(balance.getFreeCredit())
                .paidCredit(balance.getPaidCredit())
                .paidExpiresAt(balance.getPaidExpiresAt())
                .totalAvailable(totalAvailable)
                .canUse(canUse)
                .checkedCost(checkCost)
                .build();
    }
}
