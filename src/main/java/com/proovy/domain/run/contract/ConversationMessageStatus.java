package com.proovy.domain.run.contract;

/** 대화 조회 메시지 상태. user 메시지는 항상 COMPLETED다. */
public enum ConversationMessageStatus {
    PENDING,
    STREAMING,
    COMPLETED,
    FAILED,
    CANCELED
}
