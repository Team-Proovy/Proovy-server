package com.proovy.domain.run.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proovy.domain.run.entity.AiRunEventType;
import com.proovy.domain.run.entity.AiRunStatus;
import com.proovy.domain.run.event.RunEventStore;
import com.proovy.domain.run.event.StoredRunEvent;
import com.proovy.domain.run.terminal.TerminalReplay;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 저장 event를 browser frame으로 복원한다. 최초 POST·GET 재접속·live가 모두 이 경로라 같은 seq는 같은 frame이다
 * (유일한 차이는 read-time {@code sourceReferences[].available}).
 * <ul>
 *   <li>activity.*·run.created·run.canceling·run.canceled·response.page.*·run.failed: 저장 payload 그대로
 *       (1.80·1.82가 browser payload 모양으로 저장한다).</li>
 *   <li>run.completed: 저장된 최소 요약 + canonical assistant message({@link TerminalReplay#completedPayload}).
 *       본문은 event에 중복 저장되어 있지 않고 terminal commit 뒤에만 message에 있으므로 commit 전 본문은 나갈 수 없다.</li>
 * </ul>
 */
@Component
public class RunFrameReader implements RunFrameSource {

    private final RunEventStore store;
    private final TerminalReplay replay;
    private final SourceAvailability availability;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public RunFrameReader(RunEventStore store, TerminalReplay replay, SourceAvailability availability,
                          JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.store = store;
        this.replay = replay;
        this.availability = availability;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<Progress> progress(UUID runId) {
        return jdbc.query("SELECT status, last_event_seq FROM ai_runs WHERE run_id = ?",
                (rs, row) -> new Progress(AiRunStatus.valueOf(rs.getString(1)).isTerminal(), rs.getLong(2)), runId)
                .stream().findFirst();
    }

    @Override
    public List<OutboundFrame> after(UUID runId, long afterSeq, int limit) {
        List<OutboundFrame> frames = new ArrayList<>();
        for (StoredRunEvent event : store.findAfter(runId, afterSeq, limit)) {
            frames.add(outbound(event));
        }
        return frames;
    }

    OutboundFrame outbound(StoredRunEvent event) {
        JsonNode payload = event.type() == AiRunEventType.RUN_COMPLETED ? completedPayload(event) : event.payload();
        String json;
        try {
            json = objectMapper.writeValueAsString(event.toFrame(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("run frame could not be serialized", e);
        }
        return new OutboundFrame(event.seq(), isTerminal(event.type()),
                OutboundFrame.sse(event.type().wireValue(), event.cursor().toString(), json));
    }

    private JsonNode completedPayload(StoredRunEvent event) {
        TerminalReplay.CompletedMessage message = replay.findCompleted(event.runId()).orElseThrow(() ->
                new IllegalStateException("run.completed without a completed assistant message"));
        long noteId = jdbc.queryForObject("SELECT note_id FROM ai_runs WHERE run_id = ?", Long.class, event.runId());
        return TerminalReplay.completedPayload(event.payload(), message,
                availability.of(noteId, List.of(message.sourceReferences())));
    }

    static boolean isTerminal(AiRunEventType type) {
        return type == AiRunEventType.RUN_COMPLETED || type == AiRunEventType.RUN_FAILED
                || type == AiRunEventType.RUN_CANCELED;
    }
}
