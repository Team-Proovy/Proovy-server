package com.proovy.domain.conversation.entity;

/**
 * 메시지 상태. USER 메시지는 항상 COMPLETED이고 ASSISTANT 메시지는
 * PENDING/STREAMING placeholder에서 terminal(COMPLETED/FAILED/CANCELED)로 한 번만 전환된다.
 */
public enum MessageStatus {
    PENDING,
    STREAMING,
    COMPLETED,
    FAILED,
    CANCELED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELED;
    }
}
