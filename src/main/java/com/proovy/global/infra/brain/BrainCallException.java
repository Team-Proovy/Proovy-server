package com.proovy.global.infra.brain;

import com.proovy.domain.run.brain.RunOperation;
import com.proovy.domain.run.brain.RunRecordState;

/**
 * Brain 내부 API 호출 실패. message·필드에는 원인 분류와 allowlist 값(HTTP status, 409의 state·existing_run_id)만
 * 담는다. 요청·응답 본문, Authorization, grant, read URL은 담지 않고 하위 예외도 붙이지 않는다.
 */
public class BrainCallException extends RuntimeException {

    private final RunOperation operation;
    private final BrainFailureKind kind;
    private final Integer httpStatus;
    private final RunRecordState conflictState;
    private final String existingRunId;

    public BrainCallException(RunOperation operation, BrainFailureKind kind, Integer httpStatus,
            RunRecordState conflictState, String existingRunId) {
        super("Brain " + operation.wireValue() + " failed: " + kind
                + (httpStatus == null ? "" : " (HTTP " + httpStatus + ")"), null, false, false);
        this.operation = operation;
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.conflictState = conflictState;
        this.existingRunId = existingRunId;
    }

    public static BrainCallException of(RunOperation operation, BrainFailureKind kind) {
        return new BrainCallException(operation, kind, null, null, null);
    }

    public static BrainCallException of(RunOperation operation, BrainFailureKind kind, int httpStatus) {
        return new BrainCallException(operation, kind, httpStatus, null, null);
    }

    public RunOperation operation() {
        return operation;
    }

    public BrainFailureKind kind() {
        return kind;
    }

    /** HTTP 응답을 받았을 때만 값이 있다. */
    public Integer httpStatus() {
        return httpStatus;
    }

    /** {@link BrainFailureKind#RUN_CONFLICT}의 record state. */
    public RunRecordState conflictState() {
        return conflictState;
    }

    /** {@link BrainFailureKind#REQUEST_ID_BOUND_TO_OTHER_RUN}에서 request_id가 이미 묶인 run. */
    public String existingRunId() {
        return existingRunId;
    }
}
