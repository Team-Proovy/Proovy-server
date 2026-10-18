package com.proovy.domain.run.stream;

import java.io.IOException;

/** 연결 하나의 출력. production 구현은 {@link SseEmitterSink}다. 호출은 {@link RunEventStream}이 직렬화한다. */
interface RunEventSink {

    /** SSE 블록(frame 또는 heartbeat comment)을 그대로 쓴다. 연결이 끊겼으면 {@link IOException}. */
    void write(String block) throws IOException;

    /** 정상 종료(terminal frame을 보냈거나 run이 사라짐·접근할 수 없게 됨). */
    void complete();

    /** 오류 종료(복원·조회 실패). 브라우저에는 terminal 없이 끊긴 stream이다. */
    void fail(Throwable error);
}
