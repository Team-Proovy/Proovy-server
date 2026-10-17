package com.proovy.domain.run.contract;

import com.proovy.global.response.ErrorCode;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * durable event ID이자 재접속 cursor {@code {runId}:{seq}} (소문자 UUID, seq 1..2^53-1, 선행 0 없음).
 * GET /api/ai-runs/{runId}/events는 {@code Last-Event-ID} header 또는 {@code lastEventId} query로 받는다.
 * POST /api/conversations는 cursor를 받지 않는다(항상 seq 1부터).
 */
public record RunEventCursor(String runId, long seq) {

    private static final Pattern FORMAT = Pattern.compile(
            "(" + BrowserRunContract.UUID.pattern() + "):([1-9][0-9]{0,15})");

    /** 형식이 맞으면 cursor. 공백·끝 줄바꿈·대문자·선행 0·2^53-1 초과는 empty. */
    public static Optional<RunEventCursor> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        Matcher matcher = FORMAT.matcher(raw);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        long seq = Long.parseLong(matcher.group(2));
        if (seq > BrowserRunContract.MAX_SEQ) {
            return Optional.empty();
        }
        return Optional.of(new RunEventCursor(matcher.group(1), seq));
    }

    /**
     * 접근 권한(404 RUN4041) 확인 뒤의 replay 시작점 판정.
     * <ol>
     *   <li>header·query가 둘 다 있고 다르면 RUN4003</li>
     *   <li>cursor 없으면 seq 1부터</li>
     *   <li>형식 위반 RUN4003 → 다른 run RUN4004 → 마지막 durable seq 초과 RUN4003</li>
     *   <li>그 외 seq+1부터. 마지막 seq면 0 frame 뒤 terminal run은 종료, 진행 중이면 live 합류</li>
     * </ol>
     */
    public static ReplayStart resolve(String runId, long lastDurableSeq, String header, String query) {
        if (header != null && query != null && !header.equals(query)) {
            return ReplayStart.rejected(ErrorCode.RUN4003);
        }
        String raw = header != null ? header : query;
        if (raw == null) {
            return ReplayStart.from(1);
        }
        Optional<RunEventCursor> cursor = parse(raw);
        if (cursor.isEmpty()) {
            return ReplayStart.rejected(ErrorCode.RUN4003);
        }
        if (!cursor.get().runId().equals(runId)) {
            return ReplayStart.rejected(ErrorCode.RUN4004);
        }
        if (cursor.get().seq() > lastDurableSeq) {
            return ReplayStart.rejected(ErrorCode.RUN4003);
        }
        return ReplayStart.from(cursor.get().seq() + 1);
    }

    /** SSE {@code id} 값. */
    @Override
    public String toString() {
        return runId + ":" + seq;
    }

    /**
     * @param fromSeq 이 seq부터 replay (error가 있으면 0)
     * @param error   RUN4003·RUN4004, 정상이면 null
     */
    public record ReplayStart(long fromSeq, ErrorCode error) {

        static ReplayStart from(long seq) {
            return new ReplayStart(seq, null);
        }

        static ReplayStart rejected(ErrorCode error) {
            return new ReplayStart(0, error);
        }

        public boolean isRejected() {
            return error != null;
        }
    }
}
