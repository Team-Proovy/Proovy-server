package com.proovy.domain.usage.service;

import com.proovy.domain.run.entity.AiRun;
import com.proovy.domain.run.entity.AiRunStatus;
import com.proovy.domain.run.entity.ResponseDisposition;
import com.proovy.domain.run.entity.RunCancelCause;
import com.proovy.domain.run.entity.RunFailureClass;

import java.util.Objects;

/**
 * 사용량 보상 결정에 필요한 run terminal 분류. ai_runs의 terminal CHECK와 같은 짝 규칙을 따른다.
 *
 * @param status              COMPLETED | FAILED | CANCELED
 * @param disposition         COMPLETED일 때만
 * @param failureClass        FAILED일 때만
 * @param cancelRequestedFrom CANCELED일 때만 (PENDING | RUNNING | RETRYING)
 * @param cancelCause         CANCELED일 때만
 */
public record RunTerminalOutcome(AiRunStatus status,
                                 ResponseDisposition disposition,
                                 RunFailureClass failureClass,
                                 AiRunStatus cancelRequestedFrom,
                                 RunCancelCause cancelCause) {

    public RunTerminalOutcome {
        Objects.requireNonNull(status, "status");
        switch (status) {
            case COMPLETED -> require(disposition != null && failureClass == null && cancelRequestedFrom == null
                    && cancelCause == null, "COMPLETED requires only disposition");
            case FAILED -> require(failureClass != null && disposition == null && cancelRequestedFrom == null
                    && cancelCause == null, "FAILED requires only failureClass");
            case CANCELED -> require(cancelRequestedFrom != null && cancelCause != null && disposition == null
                    && failureClass == null, "CANCELED requires cancelRequestedFrom and cancelCause");
            default -> throw new IllegalArgumentException("run is not terminal: " + status);
        }
        if (status == AiRunStatus.CANCELED) {
            require(cancelRequestedFrom == AiRunStatus.PENDING || cancelRequestedFrom == AiRunStatus.RUNNING
                    || cancelRequestedFrom == AiRunStatus.RETRYING, "invalid cancelRequestedFrom: " + cancelRequestedFrom);
        }
    }

    public static RunTerminalOutcome completed(ResponseDisposition disposition) {
        return new RunTerminalOutcome(AiRunStatus.COMPLETED, disposition, null, null, null);
    }

    public static RunTerminalOutcome failed(RunFailureClass failureClass) {
        return new RunTerminalOutcome(AiRunStatus.FAILED, null, failureClass, null, null);
    }

    public static RunTerminalOutcome canceled(AiRunStatus cancelRequestedFrom, RunCancelCause cancelCause) {
        return new RunTerminalOutcome(AiRunStatus.CANCELED, null, null, cancelRequestedFrom, cancelCause);
    }

    /** terminal 전환이 반영된 run에서 분류를 읽는다. 진행 중 run이면 IllegalArgumentException. */
    public static RunTerminalOutcome of(AiRun run) {
        AiRunStatus status = run.getStatus();
        return switch (status) {
            case COMPLETED -> completed(run.getResponseDisposition());
            case FAILED -> failed(run.getFailureClass());
            case CANCELED -> canceled(run.getCancelRequestedFrom(), run.getCancelCause());
            default -> throw new IllegalArgumentException("run is not terminal: " + status);
        };
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
