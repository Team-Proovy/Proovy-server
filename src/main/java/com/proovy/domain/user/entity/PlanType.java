package com.proovy.domain.user.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PlanType {
    FREE("free", 3000),        // 3GB = 3000MB
    PREMIUM("premium", 100000); // 100GB = 100000MB

    private final String displayName;
    private final int storageLimitMb;

    @JsonValue
    public String getDisplayName() {
        return displayName;
    }
}
