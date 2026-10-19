package com.proovy.domain.usage.exception;

import lombok.Getter;

/**
 * 잔액이 요청 한 단위보다 적다. 차감·ledger row를 만들지 않았고, 호출자 transaction은 rollback-only가 된다.
 * 접수 경로(1.79)가 브라우저 계약의 잔액 부족 오류로 변환한다.
 */
@Getter
public class InsufficientUsageBalanceException extends RuntimeException {

    private final int required;
    private final int available;

    public InsufficientUsageBalanceException(int required, int available) {
        super("insufficient usage balance: required=" + required + ", available=" + available);
        this.required = required;
        this.available = available;
    }
}
