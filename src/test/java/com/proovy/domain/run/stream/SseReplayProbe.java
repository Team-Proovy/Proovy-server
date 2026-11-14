package com.proovy.domain.run.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 다른 package의 Postgres 테스트가 SSE replay(GET /api/ai-runs/{runId}/events가 보내는 frame)를 그대로 읽게 한다.
 * {@link RunFrameReader#after}가 만든 SSE 블록의 {@code data:} 줄을 frame JSON으로 돌려준다.
 */
public final class SseReplayProbe {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SseReplayProbe() {
    }

    /** seq 1부터 모든 frame(마지막 event ID 없이 다시 연 연결과 같다). */
    public static List<JsonNode> frames(RunFrameReader reader, UUID runId) {
        List<JsonNode> frames = new ArrayList<>();
        for (OutboundFrame frame : reader.after(runId, 0, 1000)) {
            for (String line : frame.sse().split("\n")) {
                if (line.startsWith("data: ")) {
                    try {
                        frames.add(JSON.readTree(line.substring("data: ".length())));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
        }
        return frames;
    }
}
