package com.proovy.domain.run.stream;

import com.proovy.domain.run.contract.BrowserRunContract;

/**
 * 브라우저로 보낼 durable frame 하나의 SSE 블록.
 *
 * @param seq      durable seq. 연결 cursor는 마지막으로 보낸 frame의 seq다
 * @param terminal run.completed·run.failed·run.canceled — 보낸 뒤 연결을 닫는다
 * @param sse      {@code event: <type>\nid: <runId>:<seq>\ndata: <frame JSON>\n\n}
 */
record OutboundFrame(long seq, boolean terminal, String sse) {

    /** heartbeat는 frame이 아니다: id·event·data가 없고 저장·replay되지 않는다. */
    static final String HEARTBEAT = BrowserRunContract.HEARTBEAT_COMMENT;

    /** browser-run-v1 fixture {@code sse}와 같은 필드 순서. data JSON은 한 줄이다(문자열 안 줄바꿈은 escape된다). */
    static String sse(String type, String id, String json) {
        if (json.indexOf('\n') >= 0 || json.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("SSE data must be a single line");
        }
        return "event: " + type + "\nid: " + id + "\ndata: " + json + "\n\n";
    }
}
