package com.proovy.domain.run.contract;

import com.proovy.domain.run.entity.AiRunStatus;

/** 브라우저에 노출하는 run 상태. 내부 RETRYING은 RUNNING으로 투영한다. */
public enum PublicRunStatus {
    PENDING,
    RUNNING,
    CANCELING,
    COMPLETED,
    FAILED,
    CANCELED;

    public static PublicRunStatus from(AiRunStatus status) {
        return switch (status) {
            case PENDING -> PENDING;
            case RUNNING, RETRYING -> RUNNING;
            case CANCELING -> CANCELING;
            case COMPLETED -> COMPLETED;
            case FAILED -> FAILED;
            case CANCELED -> CANCELED;
        };
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELED;
    }

    /** 사용자 취소가 가능한 상태(PENDING|RUNNING). */
    public boolean isCancelable() {
        return this == PENDING || this == RUNNING;
    }
}
