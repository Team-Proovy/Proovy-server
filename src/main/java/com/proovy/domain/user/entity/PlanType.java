package com.proovy.domain.user.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PlanType {
    FREE("free", 1024, 10),         // 1GB = 1024MB, maxFileSize = 10MB
    STANDARD("standard", 5120, 50), // 5GB = 5120MB, maxFileSize = 50MB
    PRO("pro", 10240, 100);         // 10GB = 10240MB, maxFileSize = 100MB

    private final String displayName;
    private final int storageLimitMb;
    private final int maxFileSizeMb;

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

    public long getStorageLimitBytes() {
        return (long) storageLimitMb * 1024 * 1024;
    }

    public long getMaxFileSizeBytes() {
        return (long) maxFileSizeMb * 1024 * 1024;
    }
}
