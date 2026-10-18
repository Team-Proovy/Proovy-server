package com.proovy.domain.run.terminal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proovy.domain.asset.entity.AssetStatus;
import com.proovy.domain.run.brain.SourceEvent;
import com.proovy.domain.run.brain.SourceEventType;
import com.proovy.domain.run.brain.StoredTerminal;
import com.proovy.domain.run.brain.StoredTerminalType;
import com.proovy.domain.run.contract.CanonicalJson;
import com.proovy.domain.run.entity.AiRunStatus;
import com.proovy.domain.run.entity.RunFailureClass;
import com.proovy.domain.run.entity.RunFailureCode;
import com.proovy.domain.run.event.RunEventStore;
import com.proovy.domain.run.event.SourceActivities;
import com.proovy.domain.run.event.TerminalRunEvent;
import com.proovy.domain.run.terminal.TerminalScopeReader.LockedAsset;
import com.proovy.domain.run.terminal.TerminalScopeReader.Scope;
import com.proovy.domain.run.terminal.TerminalScopeReader.ScopeNotReady;
import com.proovy.domain.run.terminal.TerminalSettlement.Outcome;
import com.proovy.domain.usage.service.RequestUsageService;
import com.proovy.domain.usage.service.RunTerminalOutcome;
import com.proovy.domain.usage.service.UsageCompensationResult;
import com.proovy.global.util.HashUtils;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * run terminal 확정의 유일한 경로(task 1.82). 현재 fence의 terminal 하나만 받아 run·assistant message·Verified Solution·
 * page/완료 event·usage 보상을 <b>한 transaction</b>에서 저장하고, commit 뒤에만 {@code RunEventSignals}가 알린다.
 * <p>
 * 진입점과 호출자:
 * <ul>
 *   <li>{@link #settle(SourceEvent)}: Brain stream의 {@code run.completed}·{@code run.failed} (1.83 coordinator)</li>
 *   <li>{@link #settle(StoredTerminal)}: status 조회로 회수한 저장 terminal (1.85 회수). {@code run.canceled}는 취소 경로(1.84)다</li>
 *   <li>{@link #failByServer}: Brain terminal 없이 Server가 최종 실패로 끝낸다 — 거부된 terminal·실행 소실·재시도 소진 (1.83·1.85)</li>
 *   <li>{@link #expireDeadline}: 요청 deadline 만료 (1.83/1.85 deadline 조회 {@code idx_ai_runs_deadline})</li>
 * </ul>
 * <h2>transaction 경계</h2>
 * 잠금 순서는 {@code notes FOR SHARE → ai_runs FOR UPDATE → assets FOR SHARE → verified_solutions FOR UPDATE →
 * credit_balance FOR UPDATE}(usage)다. 접수(1.79)·asset 삭제와 같은 Note 우선 순서라 서로 기다릴 뿐 교착하지 않는다.
 * 1.84 취소 전환도 이 순서를 따른다. 판정(중복·fence·상태·deadline·수락·재확인)은 잠금 뒤 DB의 지금 값으로 하고,
 * 거부면 아무것도 쓰지 않는다. 쓰기는 조건부 UPDATE({@code WHERE attempt·fence·status IN (...)})가 1 row일 때만 이어지고,
 * 그 UPDATE가 {@code last_event_seq + n RETURNING}으로 terminal event seq를 함께 배정한다(1.80). 중간에 실패하면 전부 rollback이다.
 * Brain 호출·네트워크 대기는 transaction 안에 없다. 직렬화·교착·연결 실패는 같은 입력으로 새 transaction을 연다(멱등).
 * <h2>상태 전이</h2>
 * <pre>
 * RUNNING|RETRYING --run.completed(현재 fence, deadline 전)--> COMPLETED (+ message COMPLETED, solution, 보상: clarification·unsupported)
 * RUNNING|RETRYING --run.failed(현재 fence, deadline 전)-----> FAILED    (+ message FAILED, 보상: 모든 최종 실패)
 * PENDING|RUNNING|RETRYING --failByServer(현재 attempt·fence)-> FAILED
 * PENDING|RUNNING|RETRYING --expireDeadline(deadline 지남)----> FAILED timeout
 * CANCELING --(1.84만)--> CANCELED. 여기서는 어떤 terminal도 받지 않는다(NOT_ACCEPTING)
 * </pre>
 */
@Slf4j
@Service
public class RunTerminalService {

    /** serialization_failure, deadlock_detected, lock_not_available (+ 연결 실패 08xxx) */
    private static final Set<String> RETRYABLE_SQL_STATES = Set.of("40001", "40P01", "55P03");
    private static final int MAX_TRANSACTIONS = 3;

    private static final String LOCK_NOTE = """
            SELECT n.note_id FROM notes n WHERE n.note_id = (SELECT note_id FROM ai_runs WHERE run_id = ?) FOR SHARE
            """;
    private static final String LOCK_RUN = """
            SELECT request_id, user_id, note_id, assistant_message_id, status, attempt, fence_token, request_deadline_at,
                   terminal_source_sha256, last_event_seq, request_snapshot::text, policy_snapshot::text,
                   terminal_source_event_id, failure_code
            FROM ai_runs WHERE run_id = ? FOR UPDATE
            """;
    private static final String RELEASE = """
            completed_at = ?, lease_owner = NULL, lease_expires_at = NULL, grant_digest = NULL, grant_expires_at = NULL,
            terminal_source_event_id = ?, terminal_source_sha256 = ?, last_event_seq = last_event_seq + ?
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private final RunEventStore eventStore;
    private final RequestUsageService usageService;
    private final TerminalScopeReader scopeReader;
    private final EntityManager entityManager;

    public RunTerminalService(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper objectMapper,
                              RunEventStore eventStore, RequestUsageService usageService,
                              EntityManager entityManager) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.objectMapper = objectMapper;
        this.eventStore = eventStore;
        this.usageService = usageService;
        this.scopeReader = new TerminalScopeReader(jdbc, objectMapper);
        this.entityManager = entityManager;
    }

    // ------------------------------------------------------------------
    // 진입점
    // ------------------------------------------------------------------

    /** Brain stream terminal. activity event는 {@link RunEventStore#appendActivity}로 보낸다. */
    public TerminalSettlement settle(SourceEvent event) {
        if (!event.isTerminal()) {
            throw new IllegalArgumentException("activity events are appended by RunEventStore");
        }
        BrainTerminal terminal = new BrainTerminal(event.runId(), event.attempt(), event.fenceToken(),
                event.sourceEventId(), event.type() == SourceEventType.RUN_COMPLETED, event.payload(),
                scope -> TerminalAcceptance.accept(event, scope));
        return settleBrain(terminal);
    }

    /** status 조회로 회수한 저장 terminal. {@code run.canceled}는 취소 경로(1.84)가 확정하므로 REJECTED다. */
    public TerminalSettlement settle(StoredTerminal stored) {
        if (stored.type() == StoredTerminalType.RUN_CANCELED) {
            return TerminalSettlement.rejected(parseRunId(stored.runId()).orElse(null), new TerminalRejection(
                    TerminalRejectionReason.NOT_PUBLICATION_TERMINAL, "type",
                    "run.canceled is settled by the cancellation path"));
        }
        BrainTerminal terminal = new BrainTerminal(stored.runId(), stored.attempt(), stored.fenceToken(),
                stored.sourceEventId(), stored.type() == StoredTerminalType.RUN_COMPLETED, stored.payload(),
                scope -> TerminalAcceptance.accept(stored, scope));
        return settleBrain(terminal);
    }

    /**
     * Brain terminal 없이 Server가 run을 최종 실패로 끝낸다(공개 message는 code별 고정 문구, 보상 포함).
     * 현재 attempt·fence일 때만 이긴다 — PENDING(attempt 0)은 {@code fenceToken = null}로 부른다.
     *
     * @param code invalid_attachment는 Brain만 판정하므로 쓸 수 없다
     */
    public TerminalSettlement failByServer(UUID runId, int attempt, String fenceToken, RunFailureCode code,
                                           boolean recoverable) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(code, "code");
        if (code == RunFailureCode.INVALID_ATTACHMENT) {
            throw new IllegalArgumentException("invalid_attachment is a Brain verdict");
        }
        return inTransactions(() -> {
            Optional<RunRow> locked = lock(runId);
            if (locked.isEmpty()) {
                return TerminalSettlement.of(Outcome.UNKNOWN_RUN, runId, null);
            }
            RunRow run = locked.get();
            if (run.isServerFailure(code) && run.attempt() == attempt && Objects.equals(run.fenceToken(), fenceToken)) {
                // 같은 Server 실패가 이미 commit됐다(연결 실패 뒤 재시도·중복 호출)
                return TerminalSettlement.duplicate(runId, run.status(), run.lastEventSeq());
            }
            if (run.status().isTerminal() || run.status() == AiRunStatus.CANCELING) {
                return TerminalSettlement.of(Outcome.NOT_ACCEPTING, runId, run.status());
            }
            if (run.attempt() != attempt || !Objects.equals(run.fenceToken(), fenceToken)) {
                return TerminalSettlement.of(Outcome.STALE_FENCE, runId, run.status());
            }
            Instant now = now();
            Failure failure = new Failure(code, PublicWording.failureClass(code), null,
                    PublicWording.failureMessage(code, null), recoverable);
            return writeFailure(run, failure, null, null, now, new Guard(
                    "attempt = ? AND fence_token IS NOT DISTINCT FROM ? AND status IN ('PENDING', 'RUNNING', 'RETRYING')",
                    attempt, fenceToken));
        });
    }

    /**
     * 요청 deadline이 지난 진행 중 run을 {@code timeout} 최종 실패로 끝낸다(보상 포함). 완료 terminal과 경합하면 run 잠금으로
     * 직렬화되어 먼저 commit한 하나만 남는다. CANCELING은 취소 경로(1.84)가 CANCELED로 끝낸다.
     */
    public TerminalSettlement expireDeadline(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        return inTransactions(() -> {
            Optional<RunRow> locked = lock(runId);
            if (locked.isEmpty()) {
                return TerminalSettlement.of(Outcome.UNKNOWN_RUN, runId, null);
            }
            RunRow run = locked.get();
            if (run.isServerFailure(RunFailureCode.TIMEOUT)) {
                return TerminalSettlement.duplicate(runId, run.status(), run.lastEventSeq());
            }
            if (run.status().isTerminal() || run.status() == AiRunStatus.CANCELING) {
                return TerminalSettlement.of(Outcome.NOT_ACCEPTING, runId, run.status());
            }
            Instant now = now();
            if (run.deadline().isAfter(now)) {
                return TerminalSettlement.of(Outcome.DEADLINE_NOT_REACHED, runId, run.status());
            }
            RunFailureCode code = RunFailureCode.TIMEOUT;
            Failure failure = new Failure(code, PublicWording.failureClass(code), null,
                    PublicWording.failureMessage(code, null), true);
            return writeFailure(run, failure, null, null, now, new Guard(
                    "status IN ('PENDING', 'RUNNING', 'RETRYING') AND request_deadline_at <= ?", time(now)));
        });
    }

    // ------------------------------------------------------------------
    // Brain terminal
    // ------------------------------------------------------------------

    private record BrainTerminal(String wireRunId, int attempt, String fenceToken, String sourceEventId,
                                 boolean completed, JsonNode payload,
                                 Function<TerminalAcceptanceScope, AcceptedTerminal> accept) {
    }

    private TerminalSettlement settleBrain(BrainTerminal terminal) {
        Optional<UUID> parsed = parseRunId(terminal.wireRunId());
        if (parsed.isEmpty()) {
            return TerminalSettlement.of(Outcome.UNKNOWN_RUN, null, null);
        }
        UUID runId = parsed.get();
        Source source = new Source(SourceActivities.storedSourceEventId(terminal.sourceEventId()),
                sourceDigest(terminal));
        return inTransactions(() -> {
            Optional<RunRow> locked = lock(runId);
            if (locked.isEmpty()) {
                return TerminalSettlement.of(Outcome.UNKNOWN_RUN, runId, null);
            }
            RunRow run = locked.get();
            if (run.status().isTerminal()) {
                return redelivery(run, source);
            }
            if (run.attempt() != terminal.attempt() || !terminal.fenceToken().equals(run.fenceToken())) {
                return TerminalSettlement.of(Outcome.STALE_FENCE, runId, run.status());
            }
            if (run.status() != AiRunStatus.RUNNING && run.status() != AiRunStatus.RETRYING) {
                // PENDING(아직 fence 없음)은 위에서 STALE_FENCE다. 여기는 CANCELING: 늦은 결과를 막는다.
                return TerminalSettlement.of(Outcome.NOT_ACCEPTING, runId, run.status());
            }
            Instant now = now();
            if (!run.deadline().isAfter(now)) {
                return TerminalSettlement.of(Outcome.DEADLINE_PASSED, runId, run.status());
            }
            Scope scope;
            AcceptedTerminal accepted;
            try {
                scope = scopeReader.read(runId, terminal.wireRunId(), run.attempt(), run.fenceToken(), run.userId(),
                        run.noteId(), run.requestSnapshot(), run.policySnapshot());
                accepted = terminal.accept().apply(scope.acceptance());
            } catch (ScopeNotReady e) {
                log.warn("terminal scope is not ready: runId={}, cause={}", runId, e.getMessage());
                return TerminalSettlement.of(Outcome.SCOPE_NOT_READY, runId, run.status());
            } catch (TerminalRejection e) {
                log.warn("terminal rejected: runId={}, reason={}, field={}", runId, e.reason(), e.field());
                return TerminalSettlement.rejected(runId, e);
            }
            Guard guard = new Guard("attempt = ? AND fence_token = ? AND status IN ('RUNNING', 'RETRYING') "
                    + "AND request_deadline_at > ?", run.attempt(), run.fenceToken(), time(now));
            String eventSourceId = sourceIdAvailable(runId, source.key()) ? terminal.sourceEventId() : null;
            if (accepted instanceof AcceptedCompletion completion) {
                try {
                    recheck(run, completion, scope);
                } catch (TerminalRejection e) {
                    log.warn("terminal recheck failed: runId={}, reason={}, field={}", runId, e.reason(), e.field());
                    return TerminalSettlement.rejected(runId, e);
                }
                return writeCompletion(run, completion, source, eventSourceId, now, guard);
            }
            AcceptedFailure failed = (AcceptedFailure) accepted;
            return writeFailure(run, new Failure(failed.code(), failed.failureClass(), failed.reason(),
                    failed.message(), failed.recoverable()), source, eventSourceId, now, guard);
        });
    }

    /**
     * 이미 terminal인 run에 다시 온 Brain terminal. 같은 source_event_id일 때만 같은 terminal로 본다 — 다른 attempt의 같은
     * 고정 문구 실패처럼 내용이 같아도 source가 다르면 다른 terminal이다(NOT_ACCEPTING). 확정 source는 run 행에 있어
     * event가 source ID 없이 저장된 경우에도 판정한다.
     */
    private TerminalSettlement redelivery(RunRow run, Source source) {
        boolean sameSource = source.key().equals(run.terminalSourceEventId());
        if (sameSource && source.digest().equals(run.terminalSourceSha256())) {
            return TerminalSettlement.duplicate(run.runId(), run.status(), run.lastEventSeq());
        }
        if (sameSource) {
            log.warn("terminal redelivered with a different outcome: runId={}", run.runId());
            return TerminalSettlement.of(Outcome.CONFLICTING_DUPLICATE, run.runId(), run.status());
        }
        return TerminalSettlement.of(Outcome.NOT_ACCEPTING, run.runId(), run.status());
    }

    /**
     * 1.81 {@link TerminalRecheck} 재확인. scope는 Note·run 잠금 뒤 같은 transaction에서 만들었다(asset {@code FOR SHARE},
     * solution은 Note 잠금 아래 같은 Note에서만 조회). 그래서 cited asset·참조 solution 재확인은 그 자체로 충족되고, 아래
     * cited asset 비교는 이미 읽은 값의 방어적 확인일 뿐(추가 쿼리 없음)이다. 실제로 새로 잠그는 것은 정정 대상
     * ({@code FOR UPDATE}로 ACTIVE 확인 — 동시 정정은 하나만 이긴다)뿐이다.
     */
    private void recheck(RunRow run, AcceptedCompletion completion, Scope scope) {
        TerminalRecheck recheck = completion.recheck();
        for (TerminalRecheck.CitedAsset cited : recheck.citedAssets()) {
            LockedAsset asset = scope.lockedAssets().get(cited.noteAssetId());
            if (asset == null || asset.noteId() != run.noteId()
                    || asset.status() != AssetStatus.ACTIVE
                    || asset.contentVersion() != cited.contentVersion()) {
                throw new TerminalRejection(TerminalRejectionReason.SOURCE_SCOPE, "payload",
                        "cited asset changed before the terminal transaction");
            }
        }
        for (UUID target : recheck.revisedSolutions()) {
            List<String> status = jdbc.queryForList(
                    "SELECT status FROM verified_solutions WHERE solution_id = ? AND note_id = ? FOR UPDATE",
                    String.class, target, run.noteId());
            if (!status.equals(List.of("ACTIVE"))) {
                throw new TerminalRejection(TerminalRejectionReason.REVISION, "payload.verified_solutions",
                        "revision target is no longer active");
            }
        }
    }

    // ------------------------------------------------------------------
    // 쓰기 (조건부 전환이 1 row일 때만)
    // ------------------------------------------------------------------

    private record Guard(String sql, Object... args) {
    }

    /** Brain terminal의 확정 식별: 저장 source_event_id 키와 {type, attempt, fence_token, payload} digest. */
    private record Source(String key, String digest) {
    }

    private record Failure(RunFailureCode code, RunFailureClass failureClass, AttachmentFailureReason reason,
                           String message, boolean recoverable) {
    }

    private TerminalSettlement writeCompletion(RunRow run, AcceptedCompletion completion, Source source,
                                               String eventSourceId, Instant now, Guard guard) {
        JsonNode pages = completion.responsePages();
        int events = pages.size() * 2 + 1;
        List<Object> args = Arrays.asList(completion.disposition().wireValue(),
                completion.clarificationReason() == null ? null : completion.clarificationReason().wireValue(),
                time(now), source.key(), source.digest(), events);
        long lastSeq = transition(run, "status = 'COMPLETED', response_disposition = ?, clarification_reason = ?, "
                + RELEASE, args, guard);

        int message = jdbc.update("""
                UPDATE chat_messages SET status = 'COMPLETED', response_pages = CAST(? AS jsonb),
                       source_references = CAST(? AS jsonb), plain_text = ?, content_sha256 = ?,
                       verification_summary = CAST(? AS jsonb), completed_at = ?
                WHERE message_id = ? AND note_id = ? AND role = 'ASSISTANT' AND status IN ('PENDING', 'STREAMING')
                """, json(pages), json(completion.sourceReferences()), completion.plainText(),
                completion.contentSha256(), json(completion.verificationSummary()), time(now),
                run.assistantMessageId(), run.noteId());
        requireOne(message, "assistant message is not an open placeholder", run);

        for (VerifiedSolutionInput solution : completion.verifiedSolutions()) {
            insertSolution(run, solution, now);
        }

        eventStore.appendTerminalEvents(run.runId(), lastSeq, completedEvents(run, completion, eventSourceId), now);
        UsageCompensationResult compensation = usageService.settle(run.userId(), run.requestId(),
                RunTerminalOutcome.completed(completion.disposition()));
        log.info("run completed: runId={}, disposition={}, solutions={}, compensation={}", run.runId(),
                completion.disposition(), completion.verifiedSolutions().size(), compensation.outcome());
        return TerminalSettlement.committed(run.runId(), AiRunStatus.COMPLETED, lastSeq, compensation);
    }

    /** @param source Brain terminal이면 확정 source, Server 실패면 null */
    private TerminalSettlement writeFailure(RunRow run, Failure failure, Source source, String eventSourceId,
                                            Instant now, Guard guard) {
        List<Object> args = Arrays.asList(failure.code().wireValue(), failure.failureClass().wireValue(), time(now),
                source == null ? null : source.key(), source == null ? null : source.digest(), 1);
        long lastSeq = transition(run, "status = 'FAILED', failure_code = ?, failure_class = ?, " + RELEASE, args,
                guard);

        int message = jdbc.update("""
                UPDATE chat_messages SET status = 'FAILED', completed_at = ?
                WHERE message_id = ? AND note_id = ? AND role = 'ASSISTANT' AND status IN ('PENDING', 'STREAMING')
                """, time(now), run.assistantMessageId(), run.noteId());
        requireOne(message, "assistant message is not an open placeholder", run);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("code", failure.code().wireValue());
        if (failure.reason() != null) {
            payload.put("reason", failure.reason().wireValue());
        }
        payload.put("message", failure.message());
        payload.put("recoverable", failure.recoverable());
        payload.put("assistantMessageId", run.assistantMessageId().toString());
        TerminalRunEvent event = eventSourceId == null ? TerminalRunEvent.runFailed(payload)
                : TerminalRunEvent.runFailed(payload, eventSourceId, run.attempt());
        eventStore.appendTerminalEvents(run.runId(), lastSeq, List.of(event), now);

        UsageCompensationResult compensation = usageService.settle(run.userId(), run.requestId(),
                RunTerminalOutcome.failed(failure.failureClass()));
        log.info("run failed: runId={}, code={}, compensation={}", run.runId(), failure.code().wireValue(),
                compensation.outcome());
        return TerminalSettlement.committed(run.runId(), AiRunStatus.FAILED, lastSeq, compensation);
    }

    /** 조건부 terminal 전환. 잠금 뒤 판정과 같은 조건이라 0 row면 불변조건 위반이다(rollback). */
    private long transition(RunRow run, String set, List<Object> setArgs, Guard guard) {
        List<Object> args = new ArrayList<>(setArgs);
        args.add(run.runId());
        args.addAll(Arrays.asList(guard.args()));
        List<Long> returned = jdbc.queryForList("UPDATE ai_runs SET " + set + " WHERE run_id = ? AND " + guard.sql()
                + " RETURNING last_event_seq", Long.class, args.toArray());
        if (returned.size() != 1) {
            throw new IllegalStateException("terminal transition lost its guard for run " + run.runId());
        }
        return returned.get(0);
    }

    private void insertSolution(RunRow run, VerifiedSolutionInput solution, Instant now) {
        UUID solutionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO verified_solutions (solution_id, public_solution_id, note_id, source_run_id,
                    assistant_message_id, schema_version, solution_version, problem_statement, problem_conditions,
                    solution_text, claims, step_marks, verification_summary, verification_version, source_ref_ids,
                    overall_coverage, content_sha256, revises_solution_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, CAST(? AS jsonb), CAST(? AS jsonb),
                        CAST(? AS jsonb), ?, CAST(? AS jsonb), ?, ?, ?, ?)
                """, solutionId, solution.publicSolutionId(), run.noteId(), run.runId(), run.assistantMessageId(),
                solution.schemaVersion(), solution.solutionVersion(), solution.problemStatement(),
                json(solution.problemConditions()), solution.solutionText(), json(solution.claims()),
                json(solution.stepMarks()), json(solution.verificationSummary()), solution.verificationVersion(),
                json(solution.sourceRefIds()), solution.overallCoverage().wireValue(), solution.contentSha256(),
                solution.revisesSolutionId(), time(now));
        if (solution.revisesSolutionId() != null) {
            // 이전 row는 content를 바꾸지 않고 상태·후속 ID만 기록한다(V1 trigger가 새 row의 revises 관계를 확인한다).
            int superseded = jdbc.update("""
                    UPDATE verified_solutions SET status = 'SUPERSEDED', superseded_by_solution_id = ?, superseded_at = ?
                    WHERE solution_id = ? AND note_id = ? AND status = 'ACTIVE'
                    """, solutionId, time(now), solution.revisesSolutionId(), run.noteId());
            requireOne(superseded, "revision target is no longer active", run);
        }
    }

    /**
     * page마다 started→completed, 마지막에 run.completed. payload는 browser-run-v1 frame 모양이되 run.completed는 본문 없는
     * 최소 요약이다 — replay가 canonical message로 {@code assistantResponse}·{@code verificationSummary}를 채운다
     * ({@link TerminalReplay}).
     */
    private List<TerminalRunEvent> completedEvents(RunRow run, AcceptedCompletion completion, String eventSourceId) {
        String messageId = run.assistantMessageId().toString();
        List<TerminalRunEvent> events = new ArrayList<>();
        JsonNode pages = completion.responsePages();
        for (int i = 0; i < pages.size(); i++) {
            JsonNode page = pages.get(i);
            ObjectNode started = objectMapper.createObjectNode();
            started.put("assistantMessageId", messageId);
            started.put("responsePageId", page.get("response_page_id").asText());
            started.put("presentation", page.get("presentation").asText());
            started.put("pageIndex", i);
            if (page.hasNonNull("title")) {
                started.put("title", page.get("title").asText());
            }
            ObjectNode completed = objectMapper.createObjectNode();
            completed.put("assistantMessageId", messageId);
            completed.put("responsePageId", page.get("response_page_id").asText());
            completed.put("pageIndex", i);
            events.add(TerminalRunEvent.pageStarted(started));
            events.add(TerminalRunEvent.pageCompleted(completed));
        }
        ObjectNode summary = TerminalReplay.completedSummary(messageId, completion.disposition(),
                completion.clarificationReason(), completion.model(), completion.tier());
        events.add(eventSourceId == null ? TerminalRunEvent.runCompleted(summary)
                : TerminalRunEvent.runCompleted(summary, eventSourceId, completion.attempt()));
        return events;
    }

    // ------------------------------------------------------------------

    private record RunRow(UUID runId, String requestId, long userId, long noteId, UUID assistantMessageId,
                          AiRunStatus status, int attempt, String fenceToken, Instant deadline,
                          String terminalSourceSha256, long lastEventSeq, JsonNode requestSnapshot,
                          JsonNode policySnapshot, String terminalSourceEventId, String failureCode) {

        /** Server가 만든(Brain source 없는) 같은 code의 실패로 이미 끝났다. */
        boolean isServerFailure(RunFailureCode code) {
            return status == AiRunStatus.FAILED && terminalSourceEventId == null
                    && code.wireValue().equals(failureCode);
        }
    }

    private Optional<RunRow> lock(UUID runId) {
        if (jdbc.queryForList(LOCK_NOTE, Long.class, runId).isEmpty()) {
            return Optional.empty();
        }
        return jdbc.query(LOCK_RUN, (rs, row) -> new RunRow(runId, rs.getString(1), rs.getLong(2), rs.getLong(3),
                rs.getObject(4, UUID.class), AiRunStatus.valueOf(rs.getString(5)), rs.getInt(6), rs.getString(7),
                rs.getObject(8, OffsetDateTime.class).toInstant(), rs.getString(9), rs.getLong(10),
                readTree(rs.getString(11)), readTree(rs.getString(12)), rs.getString(13), rs.getString(14)), runId)
                .stream().findFirst();
    }

    /** 같은 키가 이미 다른 event(예: Activity)에 쓰였으면 terminal은 source ID 없이 저장한다(1.80 인계). */
    private boolean sourceIdAvailable(UUID runId, String storedSourceId) {
        Long used = jdbc.queryForObject("SELECT count(*) FROM ai_run_events WHERE run_id = ? AND source_event_id = ?",
                Long.class, runId, storedSourceId);
        if (used != null && used > 0) {
            log.warn("terminal source_event_id is already used by another event; storing without it: runId={}", runId);
            return false;
        }
        return true;
    }

    private String sourceDigest(BrainTerminal terminal) {
        Object payload = objectMapper.convertValue(terminal.payload(), Object.class);
        return HashUtils.sha256(CanonicalJson.write(Map.of(
                "type", terminal.completed() ? "run.completed" : "run.failed", "attempt", terminal.attempt(),
                "fence_token", terminal.fenceToken(), "payload", payload)));
    }

    /**
     * 자기 transaction에서 실행한다. 호출자 transaction이 열려 있으면 거기에 참여하고(commit 뒤 공개도 그 commit을 따른다)
     * 재시도하지 않는다 — rollback-only가 된 바깥 transaction 안에서 다시 시도할 수 없기 때문이다.
     */
    private TerminalSettlement inTransactions(Supplier<TerminalSettlement> work) {
        boolean outer = TransactionSynchronizationManager.isActualTransactionActive();
        if (outer) {
            // 호출자가 같은 transaction에서 바꾼 AiRun 등을 JDBC 잠금·읽기 전에 내보낸다(RunEventStore 관례).
            // 그러지 않으면 아래 판정이 옛 값을 읽고, 이후 flush가 옛 상태로 전체 컬럼 UPDATE를 한다.
            entityManager.flush();
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> work.get());
            } catch (RuntimeException e) {
                if (outer || attempt >= MAX_TRANSACTIONS || !isRetryable(e)) {
                    throw e;
                }
                log.info("terminal transaction {} failed, retrying: cause={}", attempt, e.getClass().getSimpleName());
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

    private static void requireOne(int rows, String problem, RunRow run) {
        if (rows != 1) {
            throw new IllegalStateException(problem + " (run " + run.runId() + ")");
        }
    }

    private static Optional<UUID> parseRunId(String wire) {
        try {
            UUID id = UUID.fromString(wire);
            return id.toString().equals(wire) ? Optional.of(id) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static OffsetDateTime time(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private String json(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored run snapshot is not JSON", e);
        }
    }
}
