package com.proovy.domain.user.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PlanType {
    FREE("Free", 0, "KRW", null, 100, 0, 2, 10, 1),
    STANDARD("Standard", 6900, "KRW", "MONTHLY", 100, 2000, 10, 50, 5),
    PRO("Pro", 14900, "KRW", "MONTHLY", 100, 5000, 20, 100, 10);

    private final String displayName;
    private final int price;
    private final String currency;
    private final String billingCycle; // null for FREE
    private final int dailyCreditLimit;
    private final int monthlyCreditLimit;
    private final int noteLimit;
    private final int singleFileLimitMb;
    private final int storageLimitGb;

    @JsonValue
    public String getJsonValue() {
        return name().toLowerCase();
    }

    public int getStorageLimitMb() {
        return storageLimitGb * 1024;
    }

    public long getStorageLimitBytes() {
        return (long) storageLimitGb * 1024 * 1024 * 1024;
    }
}
