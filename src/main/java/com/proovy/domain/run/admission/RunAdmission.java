package com.proovy.domain.run.admission;

import com.proovy.domain.run.contract.PublicRunStatus;
import com.proovy.domain.run.entity.AiRun;
import java.util.Objects;
import java.util.UUID;

/**
 * commit된 접수 결과. 새 run이든 같은 requestId의 replay든 브라우저 stream은 seq 1 {@code run.created}부터 시작한다
 * (browser-run-v1 {@code admission/decisions.json}의 {@code replayFromSeq}). 접수는 Brain 실행·terminal 완료를 뜻하지 않는다 —
 * 새 run은 PENDING이고 dispatch(1.83)는 별도다.
 *
 * @param outcome 이번 호출이 run을 만들었는지(CREATED), 기존 run을 돌려주는지(REPLAYED)
 * @param status  조회 시점 공개 상태. replay에서 terminal이면 stream은 저장된 event replay 후 끝난다
 */
public record RunAdmission(
        Outcome outcome,
        UUID runId,
        String requestId,
        long noteId,
        UUID userMessageId,
        UUID assistantMessageId,
        PublicRunStatus status) {

    /** 새 run이든 replay든 첫 frame은 seq 1이다. */
    public static final long REPLAY_FROM_SEQ = 1;

    public enum Outcome {
        CREATED,
        REPLAYED
    }

    public RunAdmission {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(userMessageId, "userMessageId");
        Objects.requireNonNull(assistantMessageId, "assistantMessageId");
        Objects.requireNonNull(status, "status");
    }

    static RunAdmission of(Outcome outcome, AiRun run) {
        return new RunAdmission(outcome, run.getId(), run.getRequestId(), run.getNoteId(), run.getUserMessageId(),
                run.getAssistantMessageId(), PublicRunStatus.from(run.getStatus()));
    }

    /** 저장된 event를 다 보낸 뒤 live로 이어 붙일지(terminal이 아니면 true). */
    public boolean thenLive() {
        return !status.isTerminal();
    }
}
