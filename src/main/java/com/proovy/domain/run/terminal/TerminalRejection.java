package com.proovy.domain.run.terminal;

/**
 * terminal 수락 거부. message는 범주·필드 경로·위반 종류만 담고 payload 값(본문·URL·asset 이름)은 담지 않는다.
 */
public class TerminalRejection extends RuntimeException {

    private final TerminalRejectionReason reason;
    private final String field;

    TerminalRejection(TerminalRejectionReason reason, String field, String problem) {
        super(reason + " at " + field + ": " + problem, null, false, false);
        this.reason = reason;
        this.field = field;
    }

    public TerminalRejectionReason reason() {
        return reason;
    }

    /** 위반 위치(예: {@code assistant_response.response_pages[0].result_blocks[1].body}). */
    public String field() {
        return field;
    }
}
