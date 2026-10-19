package com.proovy.domain.run.cancel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proovy.domain.run.contract.CancelCause;
import com.proovy.domain.run.contract.PublicRunStatus;
import com.proovy.domain.run.contract.RunCanceledPayload;
import com.proovy.domain.run.contract.RunCancelingPayload;
import com.proovy.domain.run.contract.ServerFrameType;
import com.proovy.domain.run.entity.AiRunStatus;
import com.proovy.domain.run.entity.RunCancelCause;
import com.proovy.domain.run.event.RunEventStore;
import com.proovy.domain.run.event.TerminalRunEvent;
import com.proovy.domain.usage.service.RequestUsageService;
import com.proovy.domain.usage.service.RunTerminalOutcome;
import com.proovy.domain.usage.service.UsageCompensationResult;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 취소 전환의 DB 경계(task 1.84). 모든 쓰기는 호출자가 {@link #inTransactions} 안에서 {@link #lock}으로 잡은 행에 대한
 * 조건부 UPDATE이고, 0 row면 불변조건 위반으로 rollback한다.
 * <p>
 * 잠금 순서는 1.82 terminal 확정과 같다: {@code notes FOR SHARE → ai_runs FOR UPDATE → (usage) credit_balance FOR UPDATE}.
 * 접수(1.79)·asset 삭제와 같은 Note 우선 순서라 서로 기다릴 뿐 교착하지 않는다. coordinator claim은 {@code SKIP LOCKED}로
 * 이 transaction이 잡은 PENDING run을 건너뛰고, 조건 {@code status = 'PENDING' AND attempt = 0}이라 취소와 claim은 하나만 이긴다.
 * <pre>
 * PENDING             --cancel-----------------> CANCELED  (+ message CANCELED, run.canceled, 보상 PENDING_CANCELED)
 * RUNNING|RETRYING    --cancel-----------------> CANCELING (+ run.canceling, grant 폐기. lease·슬롯은 유지)
 * CANCELING           --Brain 정리 확인·timeout--> CANCELED  (+ message CANCELED, run.canceled, 차감 유지)
 * </pre>
 */
@Slf4j
@Component
public class RunCancelStore {

    /** serialization_failure, deadlock_detected, lock_not_available (+ 연결 실패 08xxx) */
    private static final Set<String> RETRYABLE_SQL_STATES = Set.of("40001", "40P01", "55P03");
    private static final int MAX_TRANSACTIONS = 3;

    private static final String LOCK_NOTE = """
            SELECT n.status FROM notes n WHERE n.note_id = (SELECT note_id FROM ai_runs WHERE run_id = ?) FOR SHARE
            """;
    private static final String LOCK_RUN = """
            SELECT user_id, request_id, note_id, assistant_message_id, status, attempt, fence_token, cancel_cause,
                   cancel_requested_from, last_event_seq
            FROM ai_runs WHERE run_id = ? FOR UPDATE
            """;
    private static final String CANCELED = """
            status = 'CANCELED', completed_at = ?, lease_owner = NULL, lease_expires_at = NULL, grant_digest = NULL,
            grant_expires_at = NULL, last_event_seq = last_event_seq + 1
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private final RunEventStore events;
    private final RequestUsageService usage;

    public RunCancelStore(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper objectMapper, RunEventStore events,
                          RequestUsageService usage) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.objectMapper = objectMapper;
        this.events = events;
        this.usage = usage;
    }

    /**
     * 잠근 run. {@code noteActive}는 Note가 ACTIVE인지(DELETING이면 공개 경로는 없는 run과 같다).
     *
     * @param cancelRequestedFrom CANCELING·CANCELED일 때만
     */
    record LockedRun(UUID runId, long userId, String requestId, long noteId, boolean noteActive,
                     UUID assistantMessageId, AiRunStatus status, int attempt, String fenceToken,
                     RunCancelCause cancelCause, AiRunStatus cancelRequestedFrom, long lastEventSeq) {
    }

    /** 결과 CANCELED 전환. */
    record Canceled(long lastSeq, UsageCompensationResult compensation) {
    }

    /** Note → run 순서로 잠근다. run이나 Note가 없으면(삭제 cascade) empty. transaction 안에서만 부른다. */
    Optional<LockedRun> lock(UUID runId) {
        List<String> note = jdbc.queryForList(LOCK_NOTE, String.class, runId);
        if (note.isEmpty()) {
            return Optional.empty();
        }
        boolean noteActive = "ACTIVE".equals(note.get(0));
        return jdbc.query(LOCK_RUN, (rs, row) -> new LockedRun(runId, rs.getLong(1), rs.getString(2), rs.getLong(3),
                noteActive, rs.getObject(4, UUID.class), AiRunStatus.valueOf(rs.getString(5)), rs.getInt(6),
                rs.getString(7), cause(rs.getString(8)), status(rs.getString(9)), rs.getLong(10)), runId)
                .stream().findFirst();
    }

    /** PENDING(attempt 0) → CANCELED + 보상. claim이 먼저 이겼으면 잠금 뒤 RUNNING이라 여기 오지 않는다. */
    Canceled cancelPending(LockedRun run, RunCancelCause cause) {
        Instant now = now();
        long lastSeq = transition(run, "cancel_cause = ?, cancel_requested_from = 'PENDING', cancel_requested_at = ?, "
                + CANCELED, "status = 'PENDING' AND attempt = 0",
                cause.wireValue(), time(now), time(now));
        return writeCanceled(run, cause, AiRunStatus.PENDING, lastSeq, now);
    }

    /**
     * RUNNING|RETRYING(현재 attempt·fence) → CANCELING + {@code run.canceling}. 이후 이 fence의 Activity는 열린 Activity의
     * {@code activity.canceled}만, terminal은 하나도 받지 않는다(1.80·1.82). lease·heartbeat는 그대로라 worker가 stream을 마저
     * 소비하고, 비terminal이라 전역 슬롯·Note Active Run 자리를 계속 차지한다. 이전 grant는 폐기한다(plan 12).
     * {@code cancel_requested_at}은 DB {@code now()}다 — cleanup-timeout을 다른 인스턴스가 DB 시각으로 비교한다.
     */
    void markCanceling(LockedRun run, RunCancelCause cause) {
        Instant now = now();
        int updated = jdbc.update("""
                UPDATE ai_runs SET status = 'CANCELING', cancel_cause = ?, cancel_requested_from = status,
                       cancel_requested_at = now(), grant_digest = NULL, grant_expires_at = NULL
                WHERE run_id = ? AND status IN ('RUNNING', 'RETRYING') AND attempt = ? AND fence_token = ?
                """, cause.wireValue(), run.runId(), run.attempt(), run.fenceToken());
        requireOne(updated, "canceling transition lost its guard", run);
        events.appendServerEvent(run.runId(), ServerFrameType.RUN_CANCELING,
                new RunCancelingPayload(run.assistantMessageId().toString(), PublicRunStatus.CANCELING), now);
    }

    /** CANCELING → CANCELED. 사용량은 1.77 정책표대로(사용자 취소는 차감 유지). */
    Canceled confirmCanceled(LockedRun run) {
        Instant now = now();
        long lastSeq = transition(run, CANCELED, "status = 'CANCELING'", time(now));
        return writeCanceled(run, run.cancelCause(), run.cancelRequestedFrom(), lastSeq, now);
    }

    private Canceled writeCanceled(LockedRun run, RunCancelCause cause, AiRunStatus from, long lastSeq,
                                   Instant now) {
        int message = jdbc.update("""
                UPDATE chat_messages SET status = 'CANCELED', completed_at = ?
                WHERE message_id = ? AND note_id = ? AND role = 'ASSISTANT' AND status IN ('PENDING', 'STREAMING')
                """, time(now), run.assistantMessageId(), run.noteId());
        requireOne(message, "assistant message is not an open placeholder", run);

        JsonNode payload = objectMapper.valueToTree(
                RunCanceledPayload.of(run.assistantMessageId().toString(), CancelCause.from(cause)));
        events.appendTerminalEvents(run.runId(), lastSeq, List.of(TerminalRunEvent.runCanceled(payload)), now);

        UsageCompensationResult compensation = usage.settle(run.userId(), run.requestId(),
                RunTerminalOutcome.canceled(from, cause));
        log.info("run canceled: runId={}, from={}, cause={}, compensation={}", run.runId(), from, cause.wireValue(),
                compensation.outcome());
        return new Canceled(lastSeq, compensation);
    }

    /** 조건부 terminal 전환. 잠금 뒤 판정과 같은 조건이라 0 row면 불변조건 위반이다(rollback). */
    private long transition(LockedRun run, String set, String guard, Object... setArgs) {
        Object[] args = Arrays.copyOf(setArgs, setArgs.length + 1);
        args[setArgs.length] = run.runId();
        List<Long> returned = jdbc.queryForList("UPDATE ai_runs SET " + set + " WHERE run_id = ? AND " + guard
                + " RETURNING last_event_seq", Long.class, args);
        if (returned.size() != 1) {
            throw new IllegalStateException("cancel transition lost its guard for run " + run.runId());
        }
        return returned.get(0);
    }

    /**
     * 자기 transaction에서 실행한다. 직렬화·교착·연결 실패만 같은 입력으로 새 transaction을 연다(판정을 잠금 뒤 다시 하므로
     * commit 여부를 모르는 실패 뒤에도 멱등이다 — 이미 바뀐 상태를 보고 ALREADY_*로 답한다). 호출자 transaction이 열려 있으면
     * 거기에 참여하고 재시도하지 않는다 — rollback-only가 된 바깥 transaction 안에서 다시 시도할 수 없다(RunTerminalService와 같은 규칙).
     */
    <T> T inTransactions(Supplier<T> work) {
        boolean outer = TransactionSynchronizationManager.isActualTransactionActive();
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> work.get());
            } catch (RuntimeException e) {
                if (outer || attempt >= MAX_TRANSACTIONS || !isRetryable(e)) {
                    throw e;
                }
                log.info("cancel transaction {} failed, retrying: cause={}", attempt, e.getClass().getSimpleName());
            }
        }
    }

    static boolean isRetryable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                String state = sql.getSQLState();
                return RETRYABLE_SQL_STATES.contains(state) || state.startsWith("08");
            }
        }
        return false;
    }

    private static void requireOne(int rows, String problem, LockedRun run) {
        if (rows != 1) {
            throw new IllegalStateException(problem + " (run " + run.runId() + ")");
        }
    }

    private static final RunCancelCause.Converter CAUSES = new RunCancelCause.Converter();

    static RunCancelCause cause(String wire) {
        return CAUSES.convertToEntityAttribute(wire);
    }

    private static AiRunStatus status(String value) {
        return value == null ? null : AiRunStatus.valueOf(value);
    }

    static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static OffsetDateTime time(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
