package com.proovy.domain.run.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.proovy.domain.run.brain.ExecutionUsage;
import com.proovy.domain.run.brain.InternalRunRequest;
import com.proovy.domain.run.entity.AiRun;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 저장된 run에서 Brain 전달 요청을 다시 만든다. 현재 설정·history·viewer·corpus를 읽지 않는다.
 * <ul>
 *   <li>의미 snapshot·deadline·limits·budget version은 {@code request_snapshot}에서만 온다.</li>
 *   <li>attempt·fence는 run 행(coordinator가 조건부 UPDATE로 배정한 값), 누적 usage는 run 행의 단조 counter다.
 *       그래서 재조회·재전달·재시작이 deadline을 늘리거나 usage를 0으로 되돌릴 수 없다.</li>
 *   <li>grant·read URL은 호출자가 이번 전달용으로 넘긴다({@link RunAssetAccess}). 저장하지 않는다.</li>
 *   <li>복원한 요청의 fingerprint·deadline·식별자가 저장 열과 다르면 {@link RunSnapshotIntegrityException}이다.</li>
 * </ul>
 * 살아 있는 실행 안의 재전달은 1.76 {@link InternalRunRequest#forRetry}를 써도 같은 결과다. 이 클래스는 재시작 뒤처럼
 * 메모리에 요청이 없을 때의 경로다.
 */
public final class RunSnapshotRestorer {

    private RunSnapshotRestorer() {
    }

    /** coordinator가 attempt·fence를 배정한(RUNNING·RETRYING) run의 현재 전달 요청. */
    public static InternalRunRequest delivery(AiRun run, RunAssetAccess access) {
        if (run.getAttempt() == null || run.getAttempt() < 1 || run.getFenceToken() == null) {
            throw new IllegalStateException("run has no assigned attempt/fence yet");
        }
        ExecutionUsage usage = new ExecutionUsage(run.getUsedLlmCalls(), run.getUsedToolCalls(),
                run.getUsedOutputTokens(), run.getUsedCostMicros(), run.getUsedPageUnderstandings());
        InternalRunRequest request = restore(run.getRequestSnapshot(), run.getRequestFingerprint(),
                run.getRequestDeadlineAt(), run.getAttempt(), run.getFenceToken(), usage, access);
        if (!request.runId().equals(run.getId().toString()) || !request.requestId().equals(run.getRequestId())
                || !request.userId().equals(String.valueOf(run.getUserId()))
                || !request.noteId().equals(String.valueOf(run.getNoteId()))) {
            throw new RunSnapshotIntegrityException("request snapshot identity does not match the run");
        }
        return request;
    }

    /**
     * 저장 열에서 요청을 복원하고 저장 fingerprint·deadline과 대조한다.
     *
     * @param storedFingerprint {@code ai_runs.request_fingerprint}
     * @param storedDeadline    {@code ai_runs.request_deadline_at}
     */
    public static InternalRunRequest restore(JsonNode requestSnapshot, String storedFingerprint,
            Instant storedDeadline, int attempt, String fenceToken, ExecutionUsage usage, RunAssetAccess access) {
        InternalRunRequest request = RunSnapshotCodec.restore(requestSnapshot, attempt, fenceToken, usage, access);
        if (!request.fingerprint().sha256().equals(storedFingerprint)) {
            throw new RunSnapshotIntegrityException("restored request fingerprint differs from the stored one");
        }
        Instant deadline = request.executionOptions().requestDeadlineAt().toInstant();
        if (!deadline.equals(storedDeadline.truncatedTo(ChronoUnit.MICROS))) {
            throw new RunSnapshotIntegrityException("restored request deadline differs from the stored one");
        }
        return request;
    }
}
