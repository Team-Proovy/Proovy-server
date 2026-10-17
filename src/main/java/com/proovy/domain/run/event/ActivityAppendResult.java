package com.proovy.domain.run.event;

/**
 * source Activity 하나의 수락 결과. 어떤 결과든 저장 실패로 예외를 던지지 않으므로 호출자(coordinator)는
 * {@link Outcome}으로 다음 행동만 고른다.
 *
 * @param seq {@link Outcome#APPENDED}면 새 seq, {@link Outcome#DUPLICATE}·{@link Outcome#CONFLICTING_DUPLICATE}면
 *            이미 저장된 seq, 나머지는 0
 */
public record ActivityAppendResult(Outcome outcome, long seq) {

    public enum Outcome {
        /** 새 durable event로 저장했다. commit 뒤 구독자에게 알린다. */
        APPENDED,
        /** 같은 {@code source_event_id}가 같은 내용으로 이미 있다. row·seq가 늘지 않았다. */
        DUPLICATE,
        /** 같은 {@code source_event_id}가 다른 type·payload로 이미 있다(Brain ID 규칙 위반). 기존 row를 유지한다. */
        CONFLICTING_DUPLICATE,
        /** 현재 run의 attempt·fence가 아니다(이전 attempt 또는 아직 배정되지 않은 attempt). */
        STALE_FENCE,
        /** run 상태가 Activity를 받지 않는다(PENDING·terminal, CANCELING의 activity.canceled 외 이벤트). */
        NOT_ACCEPTING,
        /** Activity 순서 위반: started 없는 후속, 이미 끝난 Activity, kind 불일치, 두 번째 started. */
        OUT_OF_ORDER,
        /** run이 없다(Note 삭제 cascade 포함). */
        UNKNOWN_RUN,
        /** activity-v1 계약 위반(필드 이름만 기록하고 payload는 버린다). */
        MALFORMED
    }

    static ActivityAppendResult of(Outcome outcome) {
        return new ActivityAppendResult(outcome, 0);
    }

    /** durable 저장소에 이 source event가 있다(이번에 저장했거나 이미 있었다). */
    public boolean isStored() {
        return outcome == Outcome.APPENDED || outcome == Outcome.DUPLICATE;
    }

    /**
     * 이 stream의 이후 Activity도 저장되지 않는다. coordinator는 이 stream 소비를 멈추고 run 상태·현재 fence를 다시 본다.
     * (CANCELING의 activity.canceled처럼 상태별 예외가 있어 저장 판단 자체는 매 event마다 이 store가 한다.)
     */
    public boolean isStreamObsolete() {
        return outcome == Outcome.STALE_FENCE || outcome == Outcome.UNKNOWN_RUN;
    }
}
