package com.proovy.domain.user.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PlanType {

    FREE("free", 0, 2, 10),
    STANDARD("standard", 2000, 10, 50),
    PRO("pro", 5000, 20, 100);

    private final String displayName;

    private final int monthlyCreditLimit;     // FREE=0, STANDARD=2000, PRO=5000
    private final int noteLimit;              // FREE=2, STANDARD=10, PRO=20
    private final int singleFileLimitMb;      // FREE=10, STANDARD=50, PRO=100
    private static final int PER_NOTE_LIMIT_MB = 512;
    private static final int MB_PER_GB = 1024;

    @JsonValue
    public String getDisplayName() {
        return displayName;
    }

    public int getStorageLimitMb() {
        return noteLimit * PER_NOTE_LIMIT_MB;
    }

    public double getStorageLimitGb() {
        return getStorageLimitMb() / (double) MB_PER_GB;
    }

    // bytes 변환
    public long getStorageLimitBytes() {
        return (long) getStorageLimitMb() * 1024 * 1024;
    }
}
