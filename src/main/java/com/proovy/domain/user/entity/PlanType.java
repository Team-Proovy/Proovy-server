package com.proovy.domain.user.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PlanType {
    FREE("free", 1024, 2, 30),           // 1GB, 노트 2개, 대화 30회
    STANDARD("standard", 5120, 10, 100),  // 5GB, 노트 10개, 대화 100회
    PRO("pro", 10240, 20, 300);          // 10GB, 노트 20개, 대화 300회

    private final String displayName;
    private final int storageLimitMb;
    private final int noteLimitCount;
    private final int conversationLimitPerNote;

    @JsonValue
    public String getDisplayName() {
        return displayName;
    }
}
