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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
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
 * 어느 경우든 {@code cancel_requested_at + cleanup-timeout}이 지나면 확인 없이 CANCELED로 확정한다(안전 timeout). 시각은 DB
 * {@code now()} 기준이고, 이미 지났으면 Brain을 부르지 않으며, 남은 시간이 client request-timeout보다 짧으면 호출을 그만큼만 기다린다.
 * 확정 transaction 안에서 runtime cleanup을 예약하고({@link RunClaimStore#reserveRuntimeCleanup}) commit 뒤 이 인스턴스의 worker를
 * 멈춘다({@link RunCoordinator#release}). 그 전까지 run은 비terminal이라 전역 슬롯과 Note Active Run 자리를 계속 차지한다.
 * <h2>실패 행</h2>
 * 확인이 예외로 끝난 run은 {@code failure-backoff} 동안 주기 확인에서 빼 오래된 실패 행이 batch를 막지 않게 한다(ERROR·metric).
 * 확정이 불변조건 위반(열린 assistant placeholder 없음 등)으로 계속 실패하면 운영 수동 개입 대상이다.
 * <h2>스레드</h2>
 * 내부 executor 하나({@code runCancel-*})이고 Spring {@code Executor}·{@code TaskScheduler} bean으로 노출하지 않는다(전역 기본 bean을
 * 바꾸지 않기 위해, 1.83·1.86과 같은 관례). run마다 동시에 확인 하나만 돈다. Brain 호출은 client의 request-timeout으로 끝나고
 * 그동안 DB transaction·row lock을 쥐지 않는다. lifecycle phase는 web server보다 앞이라 요청을 받기 전에 시작하고 요청이 멈춘 뒤에
 * 멈춘다. 그 밖의 구간(executor 없음)에 들어온 {@code wake}는 버려지고 주기 확인(다른 인스턴스·재시작 포함)이 줍는다 —
 * 그래서 {@code enabled=false}로만 구성한 배포에서는 그 run이 CANCELING에 남는다(점검·테스트 전용 설정).
 */
@Slf4j
@Component
public class RunCancelFinalizer implements SmartLifecycle {

    private static final int THREADS = 2;
    /** web server start/stop(Boot {@code DEFAULT_PHASE - 2048})·graceful shutdown(−1024)보다 앞. */
    private static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 4096;

    enum BrainView {
        TERMINAL,
        ABSENT,
        WINDING_DOWN,
        UNKNOWN
    }

    /** @param untilTimeout DB {@code now()} 기준 cleanup-timeout까지 남은 시간(지났으면 0) */
    private record CancelingRun(UUID runId, int attempt, String fenceToken, RunCancelCause cause,
                                Duration untilTimeout, boolean leaseExpired) {
    }

    private enum Confirmation {
        CONFIRMED,
        ALREADY_DONE,
        LEASE_LIVE
    }

    private final RunCancelStore store;
    private final RunCancelProperties properties;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<BrainRunClient> brain;
    private final ObjectProvider<RunCoordinator> coordinator;
    private final ObjectProvider<RunCoordinatorProperties> coordinatorProperties;
    private final RunClaimStore claims;
    private final MeterRegistry metrics;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    /** 확인이 실패한 run → 주기 확인에 다시 넣을 시각(System.nanoTime). */
    private final Map<UUID, Long> backoff = new ConcurrentHashMap<>();
    private final AtomicBoolean sweepFailing = new AtomicBoolean();

    private volatile ScheduledThreadPoolExecutor executor;

    public RunCancelFinalizer(RunCancelStore store, RunCancelProperties properties, JdbcTemplate jdbc,
                              TransactionTemplate tx, ObjectProvider<BrainRunClient> brain,
                              ObjectProvider<RunCoordinator> coordinator,
                              ObjectProvider<RunCoordinatorProperties> coordinatorProperties,
                              ObjectProvider<RunClaimStore> claims, ObjectProvider<MeterRegistry> metrics) {
        this.store = store;
        this.properties = properties;
        this.jdbc = jdbc;
        this.brain = brain;
        this.coordinator = coordinator;
        this.coordinatorProperties = coordinatorProperties;
        // coordinator가 꺼진 인스턴스에서도 같은 공개 hook(cleanup 예약 SQL)을 쓴다.
        this.claims = claims.getIfAvailable(() -> new RunClaimStore(jdbc, tx));
        this.metrics = metrics.getIfAvailable(SimpleMeterRegistry::new);
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
        // shutdownNow가 버린 queue 작업은 finally를 돌지 않는다 — 다시 start하면 그 run이 영구히 거부되지 않게 비운다
        inFlight.clear();
        backoff.clear();
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

    @Override
    public int getPhase() {
        return PHASE;
    }

    // ------------------------------------------------------------------
    // 진입점
    // ------------------------------------------------------------------

    /** 취소 API가 CANCELING을 commit한 직후(또는 이미 CANCELING인 run에 다시 취소가 왔을 때) 곧바로 한 번 확인한다. */
    public void wake(UUID runId) {
        submit(runId);
    }

    /** DB의 CANCELING run을 오래된 취소 요청부터 확인한다(run별 동시 확인 하나, backoff 중인 실패 행 제외). */
    void sweep() {
        try {
            long now = System.nanoTime();
            backoff.values().removeIf(until -> until - now <= 0);
            String excluded = backoff.keySet().stream().map(UUID::toString)
                    .collect(Collectors.joining(",", "{", "}"));
            List<UUID> canceling = jdbc.queryForList("""
                    SELECT run_id FROM ai_runs WHERE status = 'CANCELING' AND run_id <> ALL (CAST(? AS uuid[]))
                    ORDER BY cancel_requested_at, run_id LIMIT ?
                    """, UUID.class, excluded, properties.batchSize());
            if (sweepFailing.compareAndSet(true, false)) {
                log.info("cancel sweep recovered");
            }
            canceling.forEach(this::submit);
        } catch (RuntimeException e) {
            // DB 장애 동안 주기마다 같은 WARN을 남기지 않는다(처음 한 번, 회복 시 INFO)
            if (sweepFailing.compareAndSet(false, true)) {
                log.warn("cancel sweep failed: cause={}", e.getClass().getSimpleName());
            } else {
                log.debug("cancel sweep still failing: cause={}", e.getClass().getSimpleName());
            }
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
                    failed(runId, e);
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

    /**
     * 실패한 확인: 예외 message에는 SQL·값이 들어갈 수 있어 class 이름만 남긴다. {@code failure-backoff} 동안 주기 확인에서 빼고
     * (취소 API의 {@code wake}는 그대로 확인한다), 잠금 뒤 불변조건 위반이면 ERROR다.
     */
    private void failed(UUID runId, RuntimeException e) {
        boolean transientFailure = RunCancelStore.isRetryable(e);
        backoff.put(runId, System.nanoTime() + properties.failureBackoff().toNanos());
        metrics.counter("proovy.run.cancel.check_failures", "kind", transientFailure ? "transient" : "invariant")
                .increment();
        if (transientFailure) {
            log.warn("cancel check failed: runId={}, cause={}", runId, e.getClass().getSimpleName());
        } else {
            log.error("cancel check failed, backing off: runId={}, cause={}", runId, e.getClass().getSimpleName());
        }
    }

    /** 한 번 확인하고, 확정 조건이면 CANCELED로 바꾼다. 확정했으면 true. */
    boolean check(UUID runId) {
        Optional<CancelingRun> found = jdbc.query("""
                SELECT attempt, fence_token, cancel_cause,
                       GREATEST(0, CEIL(EXTRACT(EPOCH FROM
                           (cancel_requested_at + make_interval(secs => ?) - now())) * 1000))::bigint,
                       lease_expires_at IS NULL OR lease_expires_at <= now()
                FROM ai_runs WHERE run_id = ? AND status = 'CANCELING'
                """, (rs, row) -> new CancelingRun(runId, rs.getInt(1), rs.getString(2), RunCancelStore.cause(rs.getString(3)),
                Duration.ofMillis(rs.getLong(4)), rs.getBoolean(5)),
                properties.cleanupTimeout().toMillis() / 1000.0, runId).stream().findFirst();
        if (found.isEmpty()) {
            backoff.remove(runId);
            return false;
        }
        CancelingRun run = found.get();
        long timeoutAt = System.nanoTime() + run.untilTimeout().toNanos();
        // timeout이 이미 지났으면 Brain 응답을 기다리지 않는다(hang인 Brain이 확정·다른 확인을 늦추지 않게)
        BrainView view = run.untilTimeout().isZero() ? BrainView.UNKNOWN : askBrain(run, run.untilTimeout());
        String reason = switch (view) {
            case TERMINAL -> "brain_terminal";
            case ABSENT -> run.leaseExpired() && !executingLocally(runId) ? "brain_absent" : null;
            case WINDING_DOWN, UNKNOWN -> null;
        };
        if (reason == null && timeoutAt - System.nanoTime() <= 0) {
            log.warn("brain cleanup not confirmed before timeout, canceling anyway: runId={}, lastView={}", runId,
                    view);
            reason = "cleanup_timeout";
        }
        return reason != null && confirm(runId, reason);
    }

    private BrainView askBrain(CancelingRun run, Duration wait) {
        BrainRunClient client = brain.getIfAvailable();
        if (client == null) {
            return BrainView.UNKNOWN;
        }
        try {
            // client request-timeout 안에서, timeout까지 남은 시간보다 오래 기다리지 않는다
            RunCancelAck ack = client.cancel(RunCancelRequest.of(run.runId().toString(), run.attempt(),
                    run.fenceToken(), run.cause())).block(wait);
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
        } catch (IllegalStateException e) {
            // block(wait) 초과: cleanup-timeout이 남은 시간 안에 왔다
            log.info("brain cancel did not answer before the cleanup timeout: runId={}", run.runId());
            return BrainView.UNKNOWN;
        }
    }

    /**
     * CANCELING → CANCELED + runtime cleanup 예약을 한 transaction으로. {@code brain_absent}는 잠근 뒤 run lease 만료를 다시 본다
     * (Brain 호출 동안 늦은 heartbeat가 lease를 갱신했으면 그 worker가 아직 START를 보낼 수 있다). commit 여부를 모르는 재시도가
     * 이미 CANCELED를 보더라도 worker 정지는 한다.
     */
    private boolean confirm(UUID runId, String reason) {
        boolean requireExpiredLease = "brain_absent".equals(reason);
        Duration cleanupDelay = cleanupDelay();
        Confirmation result = store.inTransactions(() -> {
            Optional<RunCancelStore.LockedRun> locked = store.lock(runId);
            if (locked.isEmpty() || locked.get().status() != AiRunStatus.CANCELING) {
                return Confirmation.ALREADY_DONE;
            }
            if (requireExpiredLease && !leaseExpired(runId)) {
                return Confirmation.LEASE_LIVE;
            }
            Canceled canceled = store.confirmCanceled(locked.get());
            claims.reserveRuntimeCleanup(runId, cleanupDelay);
            log.info("cancellation confirmed: runId={}, reason={}, lastSeq={}", runId, reason, canceled.lastSeq());
            return Confirmation.CONFIRMED;
        });
        if (result == Confirmation.LEASE_LIVE) {
            log.info("brain record absent but the run lease was renewed, waiting: runId={}", runId);
            return false;
        }
        backoff.remove(runId);
        RunCoordinator local = coordinator.getIfAvailable();
        if (local != null) {
            local.release(runId);
        }
        if (result == Confirmation.CONFIRMED) {
            metrics.counter("proovy.run.cancel.confirmed", "reason", reason).increment();
            return true;
        }
        return false;
    }

    /** 잠근 run 행의 lease가 끝났는지(DB 시각). */
    private boolean leaseExpired(UUID runId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT lease_expires_at IS NULL OR lease_expires_at <= now() FROM ai_runs WHERE run_id = ?",
                Boolean.class, runId));
    }

    private boolean executingLocally(UUID runId) {
        RunCoordinator local = coordinator.getIfAvailable();
        return local != null && local.isExecuting(runId);
    }

    private Duration cleanupDelay() {
        RunCoordinatorProperties coordination = coordinatorProperties.getIfAvailable();
        return coordination == null ? Duration.ZERO : coordination.runtimeCleanupDelay();
    }
}
