package com.proovy.domain.run.contract;

import com.fasterxml.jackson.annotation.JsonValue;
import com.proovy.domain.run.entity.RunCancelCause;

/** run.canceled·대화 조회의 취소 원인과 cause별 고정 message. */
public enum CancelCause {
    USER_REQUESTED("user_requested", "답변 생성을 중단했습니다."),
    ASSET_DELETING("asset_deleting", "참조한 파일이 삭제되어 답변 생성을 중단했습니다."),
    BUDGET_EXHAUSTED("budget_exhausted", "처리 한도에 도달해 답변 생성을 중단했습니다.");

    private final String wireValue;
    private final String message;

    CancelCause(String wireValue, String message) {
        this.wireValue = wireValue;
        this.message = message;
    }

    public static CancelCause from(RunCancelCause cause) {
        return switch (cause) {
            case USER_REQUESTED -> USER_REQUESTED;
            case ASSET_DELETING -> ASSET_DELETING;
            case BUDGET_EXHAUSTED -> BUDGET_EXHAUSTED;
        };
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    public String message() {
        return message;
    }
}
