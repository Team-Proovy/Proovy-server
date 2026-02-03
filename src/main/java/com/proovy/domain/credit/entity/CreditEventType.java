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
    LLM_QUERY("AI 질의"),
    OCR("OCR 처리"),
    CODE_EXECUTION("코드 실행");

    private final String description;
}
