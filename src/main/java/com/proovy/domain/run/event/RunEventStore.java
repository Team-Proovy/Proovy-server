package com.proovy.domain.run.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proovy.domain.run.brain.SourceEvent;
import com.proovy.domain.run.contract.BrowserRunContract;
import com.proovy.domain.run.contract.ServerFrameType;
import com.proovy.domain.run.entity.AiRun;
import com.proovy.domain.run.entity.AiRunEventType;
import com.proovy.domain.run.entity.AiRunStatus;
import com.proovy.domain.run.event.ActivityAppendResult.Outcome;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * run별 durable event 저장소 ({@code ai_run_events}). S07의 유일한 이벤트 저장소이며 replay·terminal·Activity가 함께 쓴다.
 *
 * <h2>seq 배정</h2>
 * 모든 append는 먼저 {@code ai_runs} row를 {@code FOR UPDATE}로 잠그고 같은 transaction에서
 * {@code last_event_seq = last_event_seq + n ... RETURNING}으로 seq를 배정한 뒤 event를 insert한다.
 * <ul>
 *   <li>같은 run의 append는 row 잠금으로 직렬화되므로 seq는 1부터 빈틈없이 +1이고, <b>commit 순서 = seq 순서</b>다.
 *       그래서 seq N이 보이는 snapshot에는 N 이하가 모두 보이고, {@code seq > cursor} catch-up은 아직 commit되지 않은
 *       앞 seq를 건너뛰지 않는다.</li>
 *   <li>rollback되면 seq 증가도 함께 되돌아간다. seq는 Brain source 순서가 아니라 Server 수락 순서다.</li>
 *   <li>잠금은 append transaction 동안만 잡는다. 호출자는 Brain HTTP/SSE를 기다리는 동안 transaction을 열어 두지 않는다.</li>
 * </ul>
 *
 * <h2>source Activity 수락 ({@link #appendActivity})</h2>
 * 잠금 뒤 다음 순서로 판정한다. 저장하지 않는 결과는 run·event를 바꾸지 않는다.
 * <ol>
 *   <li>activity-v1 계약 위반 → MALFORMED (DB 접근 전)</li>
 *   <li>run 없음 → UNKNOWN_RUN</li>
 *   <li>event attempt·fence가 run의 현재 값과 다름 → STALE_FENCE</li>
 *   <li>같은 {@code (run_id, source_event_id)}가 있음 → DUPLICATE(같은 내용)·CONFLICTING_DUPLICATE. 기존 row 유지.</li>
 *   <li>상태: RUNNING·RETRYING은 모두, CANCELING은 열린 Activity의 {@code activity.canceled}(Brain wind-down 표식)만
 *       받는다. PENDING·terminal은 NOT_ACCEPTING (terminal 뒤 seq 증가는 DB trigger도 거부한다).</li>
 *   <li>Activity 순서(browser-run-v1 S6): 후속 event는 같은 activityId·kind의 앞선 started가 있어야 하고 그 Activity가
 *       끝나지 않았어야 한다. started는 Activity당 하나다 → 위반은 OUT_OF_ORDER.</li>
 * </ol>
 *
 * <h2>Server 소유·terminal event</h2>
 * {@link #appendServerEvent}(run.created·run.canceling)와 {@link #appendTerminalEvents}(page 경계·run.completed·
 * run.failed·run.canceled)는 호출자 transaction({@link Propagation#MANDATORY}) 안에서 상태 전환과 함께 commit된다.
 * 불변조건 위반은 {@link IllegalStateException}으로 transaction을 rollback시킨다.
 *
 * <h2>commit 후 알림</h2>
 * 저장한 transaction이 commit된 뒤에만 {@link RunEventSignals}로 {@code (runId, lastSeq)}를 알린다. 알림은 힌트이고,
 * 구독자는 {@link #findAfter}로 DB에서 읽는다.
 *
 * <p>JPA로 저장 중인 entity(예: 접수의 {@code AiRun})가 있으면 append 전에 flush한다. seq를 native UPDATE로 올린 뒤
 * 같은 영속성 context에 그 run의 {@code AiRun}이 있으면 refresh한다. 그러지 않으면 호출자가 같은 transaction에서 AiRun을
 * 바꿨을 때 Hibernate의 전체 컬럼 UPDATE가 옛 {@code last_event_seq}를 되써 {@code ai_runs_guard_update}(단조 증가)가
 * transaction을 rollback시킨다. refresh는 append 전 flush 뒤라 호출자의 변경을 잃지 않는다.
 */
@Slf4j
@Service
public class RunEventStore {

    private static final String LOCK_RUN = """
            SELECT note_id, status, attempt, fence_token, last_event_seq FROM ai_runs WHERE run_id = ? FOR UPDATE
            """;
    private static final String NEXT_SEQ = """
            UPDATE ai_runs SET last_event_seq = last_event_seq + 1 WHERE run_id = ? RETURNING last_event_seq
            """;
    private static final String INSERT_EVENT = """
            INSERT INTO ai_run_events (run_id, note_id, seq, event_type, source_event_id, attempt, activity_id, payload,
                                       created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
            ON CONFLICT DO NOTHING
            """;

    private static final AiRunEventType.Converter EVENT_TYPES = new AiRunEventType.Converter();

    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;
    private final RunEventSignals signals;

    public RunEventStore(JdbcTemplate jdbc, EntityManager entityManager, ObjectMapper objectMapper,
                         RunEventSignals signals) {
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
        this.signals = signals;
    }

    // ------------------------------------------------------------------
    // Brain source Activity
    // ------------------------------------------------------------------

    /**
     * stream으로 받은 source event 하나를 검사해 수락되면 저장한다. 호출자 transaction이 없으면 짧은 transaction을 새로 연다.
     * {@code run.completed}·{@code run.failed}는 MALFORMED다 — terminal은 1.81·1.82 경로로만 저장한다.
     */
    @Transactional
    public ActivityAppendResult appendActivity(SourceEvent event) {
        DurableActivity activity;
        try {
            activity = SourceActivities.toDurable(event);
        } catch (ActivityContractViolation violation) {
            // violation message에는 필드 위치만 있다. payload·입력 값은 기록하지 않는다.
            log.warn("source activity rejected: runId={}, sourceEventId={}, violation={}", event.runId(),
                    event.sourceEventId(), violation.getMessage());
            return ActivityAppendResult.of(Outcome.MALFORMED);
        }
        return append(activity);
    }

    private ActivityAppendResult append(DurableActivity activity) {
        entityManager.flush();
        UUID runId = activity.runId();
        Optional<RunRow> locked = lockIfExists(runId);
        if (locked.isEmpty()) {
            return ActivityAppendResult.of(Outcome.UNKNOWN_RUN);
        }
        RunRow run = locked.get();
        if (run.attempt() != activity.attempt() || !activity.fenceToken().equals(run.fenceToken())) {
            return rejected(activity, Outcome.STALE_FENCE);
        }

        String payload = json(activity.payload());
        List<long[]> existing = jdbc.query("""
                        SELECT seq, CASE WHEN event_type = ? AND activity_id = ? AND payload = CAST(? AS jsonb)
                                         THEN 1 ELSE 0 END
                        FROM ai_run_events WHERE run_id = ? AND source_event_id = ?
                        """, (rs, row) -> new long[]{rs.getLong(1), rs.getInt(2)},
                activity.type().wireValue(), activity.activityId(), payload, runId, activity.sourceEventId());
        if (!existing.isEmpty()) {
            boolean same = existing.get(0)[1] == 1;
            if (!same) {
                log.warn("source event id reused with different content: runId={}, sourceEventId={}", runId,
                        activity.sourceEventId());
            }
            return new ActivityAppendResult(same ? Outcome.DUPLICATE : Outcome.CONFLICTING_DUPLICATE,
                    existing.get(0)[0]);
        }

        if (!accepts(run.status(), activity.type())) {
            return rejected(activity, Outcome.NOT_ACCEPTING);
        }
        if (!inOrder(activity)) {
            return rejected(activity, Outcome.OUT_OF_ORDER);
        }

        long seq = nextSeq(runId);
        insert(runId, run.noteId(), seq, activity.type(), activity.sourceEventId(), activity.attempt(),
                activity.activityId(), payload, activity.occurredAt());
        refreshManagedRun(runId);
        signals.signalAfterCommit(runId, seq);
        return new ActivityAppendResult(Outcome.APPENDED, seq);
    }

    private static boolean accepts(AiRunStatus status, AiRunEventType type) {
        return switch (status) {
            case RUNNING, RETRYING -> true;
            // 취소 수락 뒤 진행·완료 표시는 막고, Brain wind-down이 남기는 진행 Activity의 canceled 표식만 받는다.
            case CANCELING -> type == AiRunEventType.ACTIVITY_CANCELED;
            case PENDING, COMPLETED, FAILED, CANCELED -> false;
        };
    }

    /** browser-run-v1 S6: started 하나 → 같은 kind의 후속 → terminal 하나, terminal 뒤 event 없음. */
    private boolean inOrder(DurableActivity activity) {
        // run 잠금 아래에서 Activity 이력 전체가 아니라 첫·마지막 row만 읽는다.
        List<String[]> first = jdbc.query("""
                        SELECT event_type, payload ->> 'kind' FROM ai_run_events
                        WHERE run_id = ? AND activity_id = ? ORDER BY seq LIMIT 1
                        """, (rs, row) -> new String[]{rs.getString(1), rs.getString(2)},
                activity.runId(), activity.activityId());
        if (activity.type() == AiRunEventType.ACTIVITY_STARTED) {
            return first.isEmpty();
        }
        if (first.isEmpty() || eventType(first.get(0)[0]) != AiRunEventType.ACTIVITY_STARTED
                || !activity.kind().wireValue().equals(first.get(0)[1])) {
            return false;
        }
        String last = jdbc.queryForObject("""
                SELECT event_type FROM ai_run_events WHERE run_id = ? AND activity_id = ? ORDER BY seq DESC LIMIT 1
                """, String.class, activity.runId(), activity.activityId());
        return !isActivityTerminal(eventType(last));
    }

    private static boolean isActivityTerminal(AiRunEventType type) {
        return type == AiRunEventType.ACTIVITY_COMPLETED || type == AiRunEventType.ACTIVITY_FAILED
                || type == AiRunEventType.ACTIVITY_CANCELED;
    }

    private static ActivityAppendResult rejected(DurableActivity activity, Outcome outcome) {
        log.info("source activity not stored: runId={}, sourceEventId={}, outcome={}", activity.runId(),
                activity.sourceEventId(), outcome);
        return ActivityAppendResult.of(outcome);
    }

    // ------------------------------------------------------------------
    // Server 소유 event (terminal 전)
    // ------------------------------------------------------------------

    /**
     * Server 소유 non-terminal event를 호출자 transaction에 저장하고 seq를 돌려준다.
     * <ul>
     *   <li>{@code run.created}: 접수 transaction(1.79)에서 PENDING run의 첫 event(seq 1)로만.</li>
     *   <li>{@code run.canceling}: 취소(1.84)가 같은 transaction에서 CANCELING으로 바꾼 뒤 한 번만.</li>
     * </ul>
     * page 경계·run.canceled는 {@link #appendTerminalEvents}로 저장한다.
     *
     * @param payload    1.75 payload record({@code RunCreatedPayload}·{@code RunCancelingPayload}) 또는 JSON object.
     *                   Spring ObjectMapper(브라우저 camelCase)로 직렬화한다.
     * @param occurredAt frame {@code occurredAt} — 보통 그 transaction의 상태 전환 시각
     * @throws IllegalStateException run이 없거나 상태·순서가 맞지 않는다(호출자 transaction은 rollback-only가 된다)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long appendServerEvent(UUID runId, ServerFrameType type, Object payload, Instant occurredAt) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        AiRunEventType eventType = switch (type) {
            case RUN_CREATED -> AiRunEventType.RUN_CREATED;
            case RUN_CANCELING -> AiRunEventType.RUN_CANCELING;
            case RESPONSE_PAGE_STARTED, RESPONSE_PAGE_COMPLETED, RUN_CANCELED ->
                    throw new IllegalArgumentException(type.type() + " is appended by the terminal transaction");
        };
        JsonNode body = objectMapper.valueToTree(payload);
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("payload must serialize to a JSON object");
        }
        entityManager.flush();
        RunRow run = lock(runId);
        boolean allowed = eventType == AiRunEventType.RUN_CREATED
                ? run.status() == AiRunStatus.PENDING && run.lastEventSeq() == 0
                : run.status() == AiRunStatus.CANCELING && count(runId, AiRunEventType.RUN_CANCELING) == 0;
        if (!allowed) {
            throw new IllegalStateException(eventType.wireValue() + " is not allowed for run " + runId
                    + " in status " + run.status());
        }
        long seq = nextSeq(runId);
        insert(runId, run.noteId(), seq, eventType, null, null, null, json(body), occurredAt);
        refreshManagedRun(runId);
        signals.signalAfterCommit(runId, seq);
        return seq;
    }

    // ------------------------------------------------------------------
    // terminal transaction
    // ------------------------------------------------------------------

    /**
     * terminal 전환과 같은 transaction에서 page 경계·terminal event를 저장한다(1.82 완료·실패, 1.84 취소 완료).
     * <p>
     * terminal 뒤에는 {@code last_event_seq}를 바꿀 수 없으므로(DB trigger) 호출자가 terminal 전환 UPDATE 하나에서
     * seq를 함께 배정한다:
     * <pre>
     * UPDATE ai_runs SET status = 'COMPLETED', ..., last_event_seq = last_event_seq + :n
     * WHERE run_id = :runId AND attempt = :attempt AND fence_token = :fence AND status IN (...)
     * RETURNING last_event_seq
     * </pre>
     * 그 반환값과 n개 event를 넘기면 {@code last-n+1 .. last} seq로 순서대로 insert한다.
     * <ul>
     *   <li>마지막은 run 상태에 맞는 terminal 하나: COMPLETED→run.completed, FAILED→run.failed, CANCELED→run.canceled.</li>
     *   <li>그 앞에는 run.completed일 때만 page 경계가 올 수 있고, pageIndex 0..k-1마다 started→completed 쌍이
     *       같은 responsePageId로 연속한다(S4). page·run.completed payload와 canonical page의 일치는 1.82가 검사한다.</li>
     * </ul>
     *
     * @return 첫 event의 seq
     * @throws IllegalStateException run이 terminal이 아니거나 배정된 seq·event 구성이 맞지 않는다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long appendTerminalEvents(UUID runId, long lastEventSeq, List<TerminalRunEvent> events,
                                     Instant occurredAt) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        List<TerminalRunEvent> batch = List.copyOf(events);
        entityManager.flush();
        RunRow run = lock(runId);
        if (!run.status().isTerminal()) {
            throw new IllegalStateException("run " + runId + " is not terminal (" + run.status() + ")");
        }
        if (run.lastEventSeq() != lastEventSeq) {
            throw new IllegalStateException("terminal seq " + lastEventSeq + " does not match run " + runId);
        }
        checkTerminalBatch(run.status(), batch);
        long first = lastEventSeq - batch.size() + 1;
        if (first < 1 || count(runId, null) != first - 1) {
            throw new IllegalStateException("terminal seq range is not allocated for run " + runId);
        }
        for (int i = 0; i < batch.size(); i++) {
            TerminalRunEvent event = batch.get(i);
            String sourceEventId = event.sourceEventId() == null ? null
                    : SourceActivities.storedSourceEventId(event.sourceEventId());
            if (sourceEventId != null && jdbc.queryForObject(
                    "SELECT count(*) FROM ai_run_events WHERE run_id = ? AND source_event_id = ?", Long.class,
                    runId, sourceEventId) > 0) {
                // Brain ID는 event kind를 포함해 결정되므로 일어나지 않아야 한다. 같은 키로 insert하면 unique 위반이라
                // 원인을 드러내고 rollback한다. 호출자는 source_event_id 없이(null) 다시 저장할 수 있다.
                throw new IllegalStateException("terminal source_event_id is already used in run " + runId);
            }
            insert(runId, run.noteId(), first + i, event.type(), sourceEventId, event.attempt(), null,
                    json(event.payload()), occurredAt);
        }
        refreshManagedRun(runId);
        signals.signalAfterCommit(runId, lastEventSeq);
        return first;
    }

    static void checkTerminalBatch(AiRunStatus status, List<TerminalRunEvent> batch) {
        if (batch.isEmpty()) {
            throw new IllegalStateException("terminal batch is empty");
        }
        AiRunEventType expected = switch (status) {
            case COMPLETED -> AiRunEventType.RUN_COMPLETED;
            case FAILED -> AiRunEventType.RUN_FAILED;
            case CANCELED -> AiRunEventType.RUN_CANCELED;
            default -> throw new IllegalStateException("not terminal");
        };
        if (batch.get(batch.size() - 1).type() != expected) {
            throw new IllegalStateException("terminal batch must end with " + expected.wireValue());
        }
        List<TerminalRunEvent> pages = batch.subList(0, batch.size() - 1);
        if (!pages.isEmpty() && expected != AiRunEventType.RUN_COMPLETED) {
            throw new IllegalStateException("page boundaries belong to run.completed only");
        }
        if (pages.size() % 2 != 0) {
            throw new IllegalStateException("page boundaries must be started/completed pairs");
        }
        for (int i = 0; i < pages.size(); i += 2) {
            TerminalRunEvent started = pages.get(i);
            TerminalRunEvent completed = pages.get(i + 1);
            if (started.type() != AiRunEventType.RESPONSE_PAGE_STARTED
                    || completed.type() != AiRunEventType.RESPONSE_PAGE_COMPLETED) {
                throw new IllegalStateException("page boundaries must be started/completed pairs");
            }
            long index = i / 2;
            if (pageIndex(started) != index || pageIndex(completed) != index) {
                throw new IllegalStateException("page boundaries must follow pageIndex order");
            }
            JsonNode startedId = started.payload().get("responsePageId");
            if (startedId == null || !startedId.isTextual()
                    || !startedId.equals(completed.payload().get("responsePageId"))) {
                throw new IllegalStateException("page boundary pair must share responsePageId");
            }
        }
    }

    private static long pageIndex(TerminalRunEvent event) {
        JsonNode index = event.payload().get("pageIndex");
        return index != null && index.isIntegralNumber() ? index.longValue() : -1;
    }

    // ------------------------------------------------------------------
    // 조회 (replay·catch-up)
    // ------------------------------------------------------------------

    /**
     * {@code afterSeq} 이후 commit된 event를 seq 오름차순으로 최대 {@code limit}개. cursor 없는 replay는 {@code afterSeq = 0}.
     * 결과가 limit보다 적으면 그 시점의 끝이다. 같은 seq는 항상 같은 내용이다(append-only).
     */
    @Transactional(readOnly = true)
    public List<StoredRunEvent> findAfter(UUID runId, long afterSeq, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return jdbc.query("""
                        SELECT seq, event_type, activity_id, payload::text, created_at FROM ai_run_events
                        WHERE run_id = ? AND seq > ? ORDER BY seq LIMIT ?
                        """, (rs, row) -> new StoredRunEvent(runId, rs.getLong(1), eventType(rs.getString(2)),
                        rs.getString(3), readTree(rs.getString(4)),
                        rs.getObject(5, OffsetDateTime.class).toInstant()),
                runId, afterSeq, limit);
    }

    /**
     * commit된 마지막 durable seq({@code ai_runs.last_event_seq}). run이 없으면 empty.
     * {@code RunEventCursor.resolve(runId, lastSeq, header, query)}의 범위 판정에 쓴다.
     */
    @Transactional(readOnly = true)
    public OptionalLong lastSeq(UUID runId) {
        List<Long> found = jdbc.queryForList("SELECT last_event_seq FROM ai_runs WHERE run_id = ?", Long.class, runId);
        return found.isEmpty() ? OptionalLong.empty() : OptionalLong.of(found.get(0));
    }

    // ------------------------------------------------------------------

    /**
     * 영속성 context에 이미 있는 AiRun만 DB 값으로 다시 읽는다. 없으면 {@code getReference}는 SQL 없이 초기화되지 않은
     * proxy만 만들므로 dirty checking 대상이 생기지 않는다.
     */
    private void refreshManagedRun(UUID runId) {
        AiRun managed = entityManager.getReference(AiRun.class, runId);
        if (Hibernate.isInitialized(managed)) {
            entityManager.refresh(managed);
        }
    }

    private Optional<RunRow> lockIfExists(UUID runId) {
        return jdbc.query(LOCK_RUN, (rs, row) -> new RunRow(rs.getLong(1), AiRunStatus.valueOf(rs.getString(2)),
                rs.getInt(3), rs.getString(4), rs.getLong(5)), runId).stream().findFirst();
    }

    private RunRow lock(UUID runId) {
        return lockIfExists(runId).orElseThrow(() -> new IllegalStateException("run " + runId + " does not exist"));
    }

    private long nextSeq(UUID runId) {
        return jdbc.queryForObject(NEXT_SEQ, Long.class, runId);
    }

    private long count(UUID runId, AiRunEventType type) {
        Long count = type == null
                ? jdbc.queryForObject("SELECT count(*) FROM ai_run_events WHERE run_id = ?", Long.class, runId)
                : jdbc.queryForObject("SELECT count(*) FROM ai_run_events WHERE run_id = ? AND event_type = ?",
                Long.class, runId, type.wireValue());
        return count == null ? 0 : count;
    }

    private void insert(UUID runId, long noteId, long seq, AiRunEventType type, String sourceEventId,
                        Integer attempt, String activityId, String payload, Instant occurredAt) {
        // 모든 경로의 시각을 wire 정밀도(µs)로 맞춰 저장한다(Postgres 반올림이 호출자 값과 갈리지 않게).
        OffsetDateTime createdAt = OffsetDateTime.ofInstant(BrowserRunContract.wireTime(occurredAt), ZoneOffset.UTC);
        int inserted = jdbc.update(INSERT_EVENT, runId, noteId, seq, type.wireValue(), sourceEventId, attempt,
                activityId, payload, createdAt);
        if (inserted != 1) {
            // run row 잠금 아래에서는 일어나지 않는다. 배정한 seq를 남기지 않도록 transaction을 되돌린다.
            throw new IllegalStateException("event insert conflicted for run " + runId + " seq " + seq);
        }
    }

    private String json(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("payload serialization failed");
        }
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored payload is not JSON");
        }
    }

    private static AiRunEventType eventType(String wireValue) {
        return EVENT_TYPES.convertToEntityAttribute(wireValue);
    }

    private record RunRow(long noteId, AiRunStatus status, int attempt, String fenceToken, long lastEventSeq) {
    }
}
