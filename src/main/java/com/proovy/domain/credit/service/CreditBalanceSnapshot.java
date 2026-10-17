package com.proovy.domain.credit.service;

import java.time.LocalDateTime;

/**
 * 조회용 잔액 값. 만료된 일일 크레딧은 리셋된 값으로 계산해 보여 줄 뿐 DB에 쓰지 않는다.
 * 잠그지 않은 조회가 잔액 row를 되돌려 써서 잠금 경로(차감·보상·지급)를 덮어쓰지 않게 하기 위해서다.
 */
public record CreditBalanceSnapshot(int dailyFreeCredit,
                                    int dailyFreeLimit,
                                    LocalDateTime dailyExpiresAt,
                                    int freeCredit,
                                    int paidCredit,
                                    LocalDateTime paidExpiresAt) {

    public int totalAvailable() {
        return dailyFreeCredit + freeCredit + paidCredit;
    }
}
