package com.proovy.domain.user.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PlanType {
    // 1GB = 1024MB 기준
    FREE("free", 0, null, 100, 0, 1, 10, 2),           // maxFileSize = 10MB
    STANDARD("standard", 6900, "monthly", 100, 2000, 5, 50, 10),  // maxFileSize = 50MB
    PRO("pro", 14900, "monthly", 100, 5000, 10, 102400, 20);      // maxFileSize = 100GB (100 * 1024 MB)

    private final String displayName;
    private final int price;                  // 월 가격 (KRW)
    private final String billingCycle;        // 결제 주기 (null for free)
    private final int dailyCreditLimit;       // 일일 크레딧 한도 (100 고정)
    private final int monthlyCreditLimit;     // 월간 크레딧 한도
    private final int storageLimitGb;         // 저장소 한도 (GB)
    private final int singleFileLimitMb;      // 단일 파일 크기 제한 (MB)
    private final int noteLimit;              // 최대 노트 수

    private static final String CURRENCY = "KRW";

    @JsonValue
    public String getDisplayName() {
        return displayName;
    }

    public String getCurrency() {
        return CURRENCY;
    }

    /**
     * 저장소 한도 (MB 단위)
     * 1GB = 1024MB
     */
    public int getStorageLimitMb() {
        return storageLimitGb * 1024;
    }

    /**
     * 저장소 한도 (Bytes 단위)
     */
    public long getStorageLimitBytes() {
        return (long) storageLimitGb * 1024 * 1024 * 1024;
    }

    /**
     * 단일 파일 크기 제한 (Bytes 단위)
     */
    public long getMaxFileSizeBytes() {
        return (long) singleFileLimitMb * 1024 * 1024;
    }

    /**
     * 월 최대 사용 가능 크레딧 (일일×30 + 월간)
     */
    public int getMaxMonthlyCredit() {
        return (dailyCreditLimit * 30) + monthlyCreditLimit;
    }
}
