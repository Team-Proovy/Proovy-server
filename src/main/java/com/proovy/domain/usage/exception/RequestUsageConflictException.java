package com.proovy.domain.usage.exception;

/**
 * request_id의 기존 차감이 다른 사용자 또는 다른 run에 속한다. request_id는 전역 unique이므로
 * 호출자는 상세를 노출하지 않고 거부한다(1.75 D7: 409 RUN4092).
 */
public class RequestUsageConflictException extends RuntimeException {

    public RequestUsageConflictException(String message) {
        super(message);
    }
}
