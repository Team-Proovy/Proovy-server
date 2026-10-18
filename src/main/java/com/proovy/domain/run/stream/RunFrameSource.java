package com.proovy.domain.run.stream;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@link RunEventStream}이 DB에서 읽는 것. production 구현은 {@link RunFrameReader}다. */
interface RunFrameSource {

    /** 지금 run 상태. run이 사라졌거나(Note 삭제 cascade) Note가 삭제 중이면 empty — 연결을 닫는다. */
    Optional<Progress> progress(UUID runId);

    /** {@code afterSeq} 이후 commit된 frame을 seq 오름차순으로 최대 {@code limit}개(hydration 끝난 SSE 블록). */
    List<OutboundFrame> after(UUID runId, long afterSeq, int limit);

    /**
     * @param terminal run이 terminal 상태인지. 이 값을 읽은 뒤의 {@link #after}에는 terminal event까지 모두 보인다
     *                 (terminal 전환과 terminal event는 한 transaction이다)
     * @param lastSeq  {@code ai_runs.last_event_seq}
     */
    record Progress(boolean terminal, long lastSeq) {
    }
}
