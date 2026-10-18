package com.proovy.domain.run.cancel;

import com.proovy.domain.run.brain.InternalRunContractViolation;
import com.proovy.domain.run.brain.RunCancelAck;
import com.proovy.domain.run.brain.RunCancelRequest;
import com.proovy.domain.run.cancel.RunCancelStore.Canceled;
import com.proovy.domain.run.coordinator.RunClaimStore;
import com.proovy.domain.run.coordinator.RunCoordinator;
import com.proovy.domain.run.coordinator.RunCoordinatorProperties;
import com.proovy.domain.run.entity.AiRunStatus;
import com.proovy.domain.run.entity.RunCancelCause;
import com.proovy.global.infra.brain.BrainCallException;
import com.proovy.global.infra.brain.BrainRunClient;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CANCELING run의 CANCELED 확정(task 1.84, docs/plan/05·11). 취소 API commit 직후 한 번, 그 뒤 {@code sweep-interval}마다
 * DB의 CANCELING run을 다시 확인한다 — 재시작·다른 인스턴스가 받은 취소도 이어받는다. 확정은 조건부 전환이라 여러 인스턴스가
 * 겹쳐도 하나만 이긴다.
 * <h2>한 번의 확인</h2>
 * 현재 attempt·fence로 Brain {@code cancel}을 보낸다. 멱등이라 처음이면 취소 전달, 그 뒤로는 정리 상태 조회가 된다
 * (ack {@code state}). 판정:
 * <ul>
 *   <li>{@code terminal}: Brain이 정리를 끝내고 저장했다({@code run.canceled}, 또는 취소 전에 끝난 결과) → CANCELED.</li>
 *   <li>{@code absent}: Brain record가 없다(실행 미시작). 그 run을 아직 보낼 수 있는 worker가 없을 때만
 *       (run lease 만료·이 인스턴스에서 실행 중 아님) CANCELED. 살아 있는 worker가 아직 stream을 보낼 수 있으면 기다린다 —
 *       보낸 뒤 확정하면 슬롯이 풀린 채 Brain에서 실행이 돈다.</li>
 *   <li>{@code canceling}·{@code executing}·호출 실패: 기다린다.</li>
 * </ul>
 * 어느 경우든 {@code cancel_requested_at + cleanup-timeout}이 지나면 확인 없이 CANCELED로 확정한다(안전 timeout).
 * 확정 뒤 runtime cleanup을 예약하고({@link RunClaimStore#reserveRuntimeCleanup}) 이 인스턴스의 worker를 멈춘다
 * ({@link RunCoordinator#release}). 그 전까지 run은 비terminal이라 전역 슬롯과 Note Active Run 자리를 계속 차지한다.
 * <h2>스레드</h2>
 * 내부 executor 하나({@code runCancel-*})이고 Spring {@code Executor}·{@code TaskScheduler} bean으로 노출하지 않는다(전역 기본 bean을
 * 바꾸지 않기 위해, 1.83·1.86과 같은 관례). run마다 동시에 확인 하나만 돈다. Brain 호출은 client의 request-timeout으로 끝나고
 * 그동안 DB transaction·row lock을 쥐지 않는다.
 */
@Slf4j
@Component
public class RunCancelFinalizer implements SmartLifecycle {

    private static final int THREADS = 2;

    enum BrainView {
        TERMINAL,
        ABSENT,
        WINDING_DOWN,
        UNKNOWN
    }

    private record CancelingRun(UUID runId, int attempt, String fenceToken, RunCancelCause cause,
                                Instant requestedAt, boolean leaseExpired) {
    }

    private final RunCancelStore store;
    private final RunCancelProperties properties;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<BrainRunClient> brain;
    private final ObjectProvider<RunCoordinator> coordinator;
    private final ObjectProvider<RunCoordinatorProperties> coordinatorProperties;
    private final RunClaimStore claims;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    private volatile ScheduledThreadPoolExecutor executor;

    public RunCancelFinalizer(RunCancelStore store, RunCancelProperties properties, JdbcTemplate jdbc,
                              TransactionTemplate tx, ObjectProvider<BrainRunClient> brain,
                              ObjectProvider<RunCoordinator> coordinator,
                              ObjectProvider<RunCoordinatorProperties> coordinatorProperties,
                              ObjectProvider<RunClaimStore> claims) {
        this.store = store;
        this.properties = properties;
        this.jdbc = jdbc;
        this.brain = brain;
        this.coordinator = coordinator;
        this.coordinatorProperties = coordinatorProperties;
        // coordinator가 꺼진 인스턴스에서도 같은 공개 hook(cleanup 예약 SQL)을 쓴다.
        this.claims = claims.getIfAvailable(() -> new RunClaimStore(jdbc, tx));
    }

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------

    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        AtomicInteger sequence = new AtomicInteger();
        ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(THREADS, runnable -> {
            Thread thread = new Thread(runnable, "runCancel-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        pool.setRemoveOnCancelPolicy(true);
        if (properties.enabled()) {
            long period = properties.sweepInterval().toMillis();
            pool.scheduleWithFixedDelay(this::sweep, period, period, TimeUnit.MILLISECONDS);
        }
        executor = pool;
        log.info("run cancel finalizer started: sweep={}, sweepInterval={}, cleanupTimeout={}", properties.enabled(),
                properties.sweepInterval(), properties.cleanupTimeout());
    }

    /** 확인 중인 Brain 호출은 request-timeout 안에 끝난다. 남은 CANCELING run은 다음 인스턴스의 sweep이 이어받는다. */
    @Override
    public synchronized void stop() {
        ScheduledThreadPoolExecutor pool = executor;
        if (pool == null) {
            return;
        }
        executor = null;
        pool.shutdownNow();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("run cancel finalizer did not stop in time: inFlight={}", inFlight.size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }

    // ------------------------------------------------------------------
    // 진입점
    // ------------------------------------------------------------------

    /** 취소 API가 CANCELING을 commit한 직후(또는 이미 CANCELING인 run에 다시 취소가 왔을 때) 곧바로 한 번 확인한다. */
    public void wake(UUID runId) {
        submit(runId);
    }

    /** DB의 CANCELING run을 오래된 취소 요청부터 확인한다(run별 동시 확인 하나). */
    void sweep() {
        try {
            List<UUID> canceling = jdbc.queryForList("""
                    SELECT run_id FROM ai_runs WHERE status = 'CANCELING'
                    ORDER BY cancel_requested_at, run_id LIMIT ?
                    """, UUID.class, properties.batchSize());
            canceling.forEach(this::submit);
        } catch (RuntimeException e) {
            log.warn("cancel sweep failed: cause={}", e.getClass().getSimpleName());
        }
    }

    private void submit(UUID runId) {
        ScheduledThreadPoolExecutor pool = executor;
        if (pool == null || !inFlight.add(runId)) {
            return;
        }
        try {
            pool.execute(() -> {
                try {
                    check(runId);
                } catch (RuntimeException e) {
                    // 예외 message에는 SQL·값이 들어갈 수 있어 class 이름만 남긴다. 다음 sweep이 다시 확인한다.
                    log.warn("cancel check failed: runId={}, cause={}", runId, e.getClass().getSimpleName());
                } finally {
                    inFlight.remove(runId);
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.remove(runId);
        }
    }

    // ------------------------------------------------------------------
    // 확인·확정
    // ------------------------------------------------------------------

    /** 한 번 확인하고, 확정 조건이면 CANCELED로 바꾼다. 확정했으면 true. */
    boolean check(UUID runId) {
        Optional<CancelingRun> found = jdbc.query("""
                SELECT attempt, fence_token, cancel_cause, cancel_requested_at,
                       lease_expires_at IS NULL OR lease_expires_at <= now()
                FROM ai_runs WHERE run_id = ? AND status = 'CANCELING'
                """, (rs, row) -> new CancelingRun(runId, rs.getInt(1), rs.getString(2), RunCancelStore.cause(rs.getString(3)),
                rs.getObject(4, OffsetDateTime.class).toInstant(), rs.getBoolean(5)), runId).stream().findFirst();
        if (found.isEmpty()) {
            return false;
        }
        CancelingRun run = found.get();
        BrainView view = askBrain(run);
        String reason = switch (view) {
            case TERMINAL -> "brain_terminal";
            case ABSENT -> run.leaseExpired() && !executingLocally(runId) ? "brain_absent" : null;
            case WINDING_DOWN, UNKNOWN -> null;
        };
        if (reason == null && !RunCancelStore.now().isBefore(run.requestedAt().plus(properties.cleanupTimeout()))) {
            log.warn("brain cleanup not confirmed before timeout, canceling anyway: runId={}, lastView={}", runId,
                    view);
            reason = "cleanup_timeout";
        }
        return reason != null && confirm(runId, reason);
    }

    private BrainView askBrain(CancelingRun run) {
        BrainRunClient client = brain.getIfAvailable();
        if (client == null) {
            return BrainView.UNKNOWN;
        }
        try {
            RunCancelAck ack = client.cancel(RunCancelRequest.of(run.runId().toString(), run.attempt(),
                    run.fenceToken(), run.cause())).block();
            if (ack == null) {
                return BrainView.UNKNOWN;
            }
            log.debug("brain cancel ack: runId={}, outcome={}, state={}", run.runId(), ack.outcome(), ack.state());
            return switch (ack.state()) {
                case TERMINAL -> BrainView.TERMINAL;
                case ABSENT -> BrainView.ABSENT;
                case CANCELING, EXECUTING -> BrainView.WINDING_DOWN;
            };
        } catch (BrainCallException e) {
            log.info("brain cancel failed: runId={}, kind={}, httpStatus={}", run.runId(), e.kind(), e.httpStatus());
            return BrainView.UNKNOWN;
        } catch (InternalRunContractViolation e) {
            log.error("cancel request cannot be built from the run row: runId={}", run.runId());
            return BrainView.UNKNOWN;
        }
    }

    private boolean confirm(UUID runId, String reason) {
        Optional<Canceled> canceled = store.inTransactions(() -> store.lock(runId)
                .filter(locked -> locked.status() == AiRunStatus.CANCELING)
                .map(store::confirmCanceled));
        if (canceled.isEmpty()) {
            return false;
        }
        log.info("cancellation confirmed: runId={}, reason={}, lastSeq={}", runId, reason, canceled.get().lastSeq());
        reserveCleanup(runId);
        RunCoordinator local = coordinator.getIfAvailable();
        if (local != null) {
            local.release(runId);
        }
        return true;
    }

    private boolean executingLocally(UUID runId) {
        RunCoordinator local = coordinator.getIfAvailable();
        return local != null && local.isExecuting(runId);
    }

    private void reserveCleanup(UUID runId) {
        RunCoordinatorProperties coordination = coordinatorProperties.getIfAvailable();
        Duration delay = coordination == null ? Duration.ZERO : coordination.runtimeCleanupDelay();
        try {
            claims.reserveRuntimeCleanup(runId, delay);
        } catch (RuntimeException e) {
            log.warn("runtime cleanup reservation failed: runId={}, cause={}", runId, e.getClass().getSimpleName());
        }
    }
}
