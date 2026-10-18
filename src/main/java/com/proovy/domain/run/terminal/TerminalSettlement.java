package com.proovy.domain.run.terminal;

import com.proovy.domain.run.entity.AiRunStatus;
import com.proovy.domain.usage.service.UsageCompensationResult;
import java.util.UUID;

/**
 * terminal 확정 시도 하나의 결과({@link RunTerminalService}). {@link Outcome#COMMITTED}만 이번 호출이 run·message·
 * solution·event·usage를 바꿨고, 나머지는 아무것도 저장하지 않았다.
 *
 * @param runStatus        COMMITTED·DUPLICATE·CONFLICTING_DUPLICATE·NOT_ACCEPTING이면 지금 run 상태, 그 외 null
 * @param lastSeq          COMMITTED·DUPLICATE면 run의 마지막 durable seq(terminal event), 그 외 null
 * @param compensation     COMMITTED면 이번 transaction의 usage 보상 결과, 그 외 null
 * @param rejection        REJECTED면 거부 범주, 그 외 null
 * @param rejectionField   REJECTED면 위반 위치(payload 값 없음), 그 외 null
 */
public record TerminalSettlement(
        Outcome outcome,
        UUID runId,
        AiRunStatus runStatus,
        Long lastSeq,
        UsageCompensationResult compensation,
        TerminalRejectionReason rejection,
        String rejectionField) {

    public enum Outcome {
        /** 이번 transaction이 terminal을 확정했다(commit됨). commit 뒤 신호가 나갔다 */
        COMMITTED,
        /** 같은 terminal(같은 source_event_id·같은 내용)이 이미 확정되어 있다. 아무것도 바꾸지 않았다 */
        DUPLICATE,
        /** 같은 source_event_id로 다른 결과가 왔다(terminal-v2 redelivery {@code changed_outcome}). 기존 결과를 유지한다 */
        CONFLICTING_DUPLICATE,
        /** 현재 attempt·fence가 아니다(이전 attempt의 늦은 결과). stream을 버리고 run을 다시 본다 */
        STALE_FENCE,
        /** run이 terminal을 받을 상태가 아니다: PENDING·CANCELING이거나 이미 다른 terminal로 끝났다 */
        NOT_ACCEPTING,
        /** 요청 deadline이 지났다. 결과를 버리고 {@link RunTerminalService#expireDeadline}로 실패 확정한다 */
        DEADLINE_PASSED,
        /** deadline 만료 확정을 요청했지만 아직 deadline 전이다 */
        DEADLINE_NOT_REACHED,
        /** run이 없다(run_id가 UUID가 아니거나 Note 삭제로 사라짐) */
        UNKNOWN_RUN,
        /** terminal이 수락 검사(1.81)나 transaction 재확인(TOCTOU)을 통과하지 못했다. run은 그대로다 */
        REJECTED,
        /** 수락 기준(scope)을 만들 수 없다 — page_count 없는 PDF dependency 등 데이터 미준비. run은 그대로다 */
        SCOPE_NOT_READY
    }

    static TerminalSettlement committed(UUID runId, AiRunStatus status, long lastSeq,
            UsageCompensationResult compensation) {
        return new TerminalSettlement(Outcome.COMMITTED, runId, status, lastSeq, compensation, null, null);
    }

    static TerminalSettlement duplicate(UUID runId, AiRunStatus status, long lastSeq) {
        return new TerminalSettlement(Outcome.DUPLICATE, runId, status, lastSeq, null, null, null);
    }

    static TerminalSettlement of(Outcome outcome, UUID runId, AiRunStatus status) {
        return new TerminalSettlement(outcome, runId, status, null, null, null, null);
    }

    static TerminalSettlement rejected(UUID runId, TerminalRejection rejection) {
        return new TerminalSettlement(Outcome.REJECTED, runId, null, null, null, rejection.reason(),
                rejection.field());
    }

    /** 이 terminal이 저장되어 있다(이번 호출 또는 이전 같은 전달). 같은 전달을 다시 보낼 필요가 없다. */
    public boolean isSettled() {
        return outcome == Outcome.COMMITTED || outcome == Outcome.DUPLICATE;
    }

    /** 이 attempt의 stream·결과는 더 쓸 데가 없다 — 소비를 멈추고 run 상태를 다시 본다. */
    public boolean isObsolete() {
        return outcome == Outcome.STALE_FENCE || outcome == Outcome.UNKNOWN_RUN || outcome == Outcome.NOT_ACCEPTING
                || outcome == Outcome.CONFLICTING_DUPLICATE;
    }
}
