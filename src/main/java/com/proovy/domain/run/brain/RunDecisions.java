package com.proovy.domain.run.brain;

/**
 * Brain {@code decide_run_request} contention 표(run-decisions-v2)의 Java 사본. Brain이 판정하고 Server는
 * 응답 해석과 coordinator 판단에 같은 표를 쓴다.
 * <pre>
 * | operation ↓ / record → | ABSENT | EXECUTING     | CANCELING  | TERMINAL        |
 * | stream (fp match)      | START  | OBSERVE       | REJECT     | RETURN_TERMINAL |
 * | stream (fp differs)    | START  | REJECT        | REJECT     | REJECT          |
 * | status                 | REPORT | REPORT        | REPORT     | REPORT          |
 * | cancel                 | REJECT | ACCEPT_CANCEL | ACK_CANCEL | REJECT          |
 * | cleanup                | NOOP   | REJECT        | REJECT     | CLEAN           |
 * </pre>
 * binding override: 다른 run_id에 묶인 request_id의 stream은 표보다 먼저 REJECT
 * ({@code request_id_bound_to_other_run}, 라이브 record·cleanup tombstone 모두).
 */
public final class RunDecisions {

    public static final String REQUEST_ID_BOUND_TO_OTHER_RUN = "request_id_bound_to_other_run";

    private RunDecisions() {
    }

    public static RunDecision decide(RunOperation operation, RunRecordState state, boolean fingerprintMatch) {
        return switch (operation) {
            case STREAM -> switch (state) {
                case ABSENT -> RunDecision.START;
                case EXECUTING -> fingerprintMatch ? RunDecision.OBSERVE : RunDecision.REJECT;
                case TERMINAL -> fingerprintMatch ? RunDecision.RETURN_TERMINAL : RunDecision.REJECT;
                case CANCELING -> RunDecision.REJECT;
            };
            case STATUS -> RunDecision.REPORT;
            case CANCEL -> switch (state) {
                case EXECUTING -> RunDecision.ACCEPT_CANCEL;
                case CANCELING -> RunDecision.ACK_CANCEL;
                case ABSENT, TERMINAL -> RunDecision.REJECT;
            };
            case CLEANUP -> switch (state) {
                case TERMINAL -> RunDecision.CLEAN;
                case ABSENT -> RunDecision.NOOP;
                case EXECUTING, CANCELING -> RunDecision.REJECT;
            };
        };
    }

    /** request_id가 다른 run에 묶였는지 먼저 본 뒤 표를 적용한다. */
    public static RunDecision decideStream(boolean requestIdBoundToOtherRun, RunRecordState state,
            boolean fingerprintMatch) {
        return requestIdBoundToOtherRun ? RunDecision.REJECT : decide(RunOperation.STREAM, state, fingerprintMatch);
    }
}
