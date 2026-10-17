package com.proovy.domain.run.contract;

/** RUNNING 취소 시 1회. 이후에는 진행 중 activity의 종료와 run.canceled만 온다. */
public record RunCancelingPayload(String assistantMessageId, PublicRunStatus status) {
}
