package com.proovy.domain.credit.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CreditEventType {
    DAILY_RESET("일일 무료 크레딧 지급"),
    MONTHLY_GRANT("구독 크레딧 지급"),
    MONTHLY_EXPIRE("유료 크레딧 소멸"),
    SIGNUP_BONUS("가입 보너스"),
    /** 일반 AI 요청 사용량 차감 (request_id당 한 번, RequestUsageService) */
    LLM_QUERY("AI 질의"),
    /** 일반 AI 요청 사용량 보상 (request_id당 최대 한 번, RequestUsageService) */
    LLM_QUERY_COMPENSATION("AI 질의 사용량 보상");

    private final String description;
}
