package com.proovy.domain.run.coordinator;

import com.proovy.domain.run.coordinator.RunClaimStore.StaleRun;
import com.proovy.domain.run.coordinator.RunExecution.Mode;
import com.proovy.domain.run.coordinator.RunExecution.StopReason;
import com.proovy.domain.run.terminal.RunTerminalService;
import com.proovy.domain.run.terminal.TerminalSettlement;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * 단일 Server run coordinator(task 1.83, docs/plan/05·09). 브라우저 연결과 독립적으로 commit된 PENDING run을 claim해
 * 전역 유한 슬롯 안에서 Brain에 위임하고 결과를 1.80 event store·1.82 terminal 확정으로 넘긴다.
 * <h2>단일 coordinator</h2>
 * DB coordinator lease({@code run_coordinator_leases})를 가진 인스턴스 하나만 claim·deadline sweep을 한다. lease가 없는
 * 인스턴스는 passive로 남아 heartbeat 주기마다 lease를 다시 시도한다. claim transaction은 lease를 {@code FOR SHARE}로
 * 다시 확인하므로 lease가 넘어간 뒤의 claim은 없다.
 * <h2>전역 슬롯</h2>
 * 빈 슬롯 = min(로컬 semaphore, {@code maxConcurrentRuns} − DB의 비terminal 실행 수({@code attempt >= 1},
 * RUNNING·RETRYING·CANCELING, lease 무관)). 다른 인스턴스·이전 프로세스·heartbeat가 끊긴 실행도 terminal이 될 때까지
 * 슬롯을 차지한다(Brain에서 아직 돌 수 있다). dispatch는 lock으로 한 번에 하나만 돈다. 슬롯이 없으면 run은 PENDING으로
 * 남는다. worker pool의 대기열은 semaphore가 상한을 지키므로 길이가 0..max로 유한하다(슬롯 반환 직후 thread가 아직
 * 비지 않은 순간에도 다음 run을 거부하지 않는다). Brain의 503 capacity는 슬롯의 대체물이 아니라 재전송 대상이다.
 * <h2>슬롯 해제</h2>
 * 로컬 permit은 worker가 끝날 때 항상 반환한다: terminal 확정(SETTLED), 이 attempt가 현재가 아님(OBSOLETE: 다른 terminal·
 * CANCELING·fence 교체), 멈춤(STOPPED: 종료·lease 상실), 로컬 오류(LOCAL_ERROR). OBSOLETE·STOPPED·LOCAL_ERROR에서 run이
 * 아직 비terminal이면 heartbeat는 멈추지만 전역 슬롯은 DB 수로 계속 차지한다 — terminal이 되어야 풀린다.
 * <h2>stale 회수 (1.85)</h2>
 * lease가 만료됐고 이 인스턴스에 worker가 없는 {@code RUNNING|RETRYING} run(이전 프로세스·종료·lease 상실·로컬 오류)을
 * 같은 attempt·fence로 인수해 status부터 수습한다({@link RunExecution.Mode#RECOVER}). 인수 worker도 로컬 permit을 잡는다.
 * 전역 슬롯은 그 run이 이미 비terminal 수로 차지하고 있어 새로 세지 않는다. CANCELING은 취소 경로(1.84) 소유라 고르지 않는다.
 * <h2>스레드</h2>
 * 내부 executor 두 개(control: claim·heartbeat·sweep, worker: run별)이며 Spring bean으로 노출하지 않는다 — 전역 기본
 * {@code taskScheduler}·{@code applicationTaskExecutor}를 바꾸지 않기 위해서다. 스레드 이름은 {@code runCoordinator-*}.
 */
@Slf4j
public class RunCoordinator implements SmartLifecycle {

    private static final int DEADLINE_BATCH = 50;

    private final RunCoordinatorProperties properties;
    private final RunClaimStore claims;
    private final RunTerminalService terminals;
    private final RunExecution.Dependencies dependencies;
    private final RunCoordinatorMetrics metrics;
    private final String owner;
    private final Semaphore slots;
    private final Map<UUID, RunExecution> executions = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean leader = new AtomicBoolean();
    private final AtomicBoolean dispatchQueued = new AtomicBoolean();
    private final ReentrantLock dispatchLock = new ReentrantLock();
    private final AtomicBoolean redispatch = new AtomicBoolean();

    private ScheduledExecutorService control;
    private ThreadPoolExecutor workers;

    RunCoordinator(RunCoordinatorProperties properties, RunClaimStore claims, RunTerminalService terminals,
            RunExecution.Dependencies dependencies, RunCoordinatorMetrics metrics) {
        this.properties = properties;
        this.claims = claims;
        this.terminals = terminals;
        this.dependencies = dependencies;
        this.metrics = metrics;
        this.owner = dependencies.owner();
        this.slots = new Semaphore(properties.maxConcurrentRuns());
        metrics.gauges(executions::size, properties.maxConcurrentRuns(), () -> leader.get() ? 1 : 0);
    }

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        int max = properties.maxConcurrentRuns();
        control = Executors.newScheduledThreadPool(2, threads("runCoordinator-control-"));
        workers = workerPool(max);
        log.info("run coordinator starting: owner={}, maxConcurrentRuns={}, brainCapacity={}", owner, max,
                properties.brainCapacity().total());
        control.scheduleWithFixedDelay(guarded("heartbeat", this::heartbeat), 0,
                properties.heartbeatInterval().toMillis(), TimeUnit.MILLISECONDS);
        control.scheduleWithFixedDelay(guarded("dispatch", this::dispatch), properties.pollInterval().toMillis(),
                properties.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
        control.scheduleWithFixedDelay(guarded("deadline", this::sweepDeadlines),
                properties.deadlineSweepInterval().toMillis(), properties.deadlineSweepInterval().toMillis(),
                TimeUnit.MILLISECONDS);
        control.scheduleWithFixedDelay(guarded("recovery", this::recoverStale),
                properties.recovery().interval().toMillis(), properties.recovery().interval().toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /**
     * claim을 멈추고 coordinator lease를 내놓은 뒤 worker에 멈춤 신호를 보낸다. 전체 대기는 {@code shutdownTimeout} 하나로
     * 묶는다(Cloud Run 종료 유예 안). 진행 중 run은 terminal이 아니라 lease만 만료된 채 남는다(transport 종료를 성공으로
     * 보지 않는다, Brain 실행은 취소하지 않는다). 그 run은 terminal이 될 때까지 전역 슬롯을 차지하고 회수는 1.85다.
     */
    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        long deadline = System.nanoTime() + properties.shutdownTimeout().toNanos();
        control.shutdownNow();
        try {
            // 진행 중인 heartbeat·claim이 끝난 뒤에 worker를 멈춘다 — 늦은 heartbeat가 만료시킨 lease를 되살리지 않게.
            if (!control.awaitTermination(remaining(deadline) / 2, TimeUnit.NANOSECONDS)) {
                log.warn("run coordinator control tasks did not stop in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // worker 대기보다 먼저 내놓는다. 슬롯 상한은 DB 실행 수가 지키므로 다음 인스턴스가 바로 인수해도 된다.
            leader.set(false);
            try {
                claims.releaseCoordinatorLease(owner);
            } catch (RuntimeException e) {
                log.warn("coordinator lease release failed: cause={}", e.getClass().getSimpleName());
            }
        }
        executions.values().forEach(execution -> execution.stop(StopReason.SHUTDOWN));
        workers.shutdown();
        try {
            if (!workers.awaitTermination(remaining(deadline), TimeUnit.NANOSECONDS)) {
                log.warn("run coordinator workers did not stop in time: remaining={}", executions.size());
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
        log.info("run coordinator stopped: owner={}", owner);
    }

    private static long remaining(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    // ------------------------------------------------------------------
    // 1.84·1.85 hook
    // ------------------------------------------------------------------

    /** 이 인스턴스에서 그 run의 worker가 돌고 있는지(1.85: 살아 있는 worker가 있는 run은 stale 회수 대상이 아니다). */
    public boolean isExecuting(UUID runId) {
        return executions.containsKey(runId);
    }

    /**
     * 그 run의 로컬 stream 소비를 멈춘다(슬롯 반환, Brain 실행은 계속). 1.84가 CANCELING 뒤 cancel 확인을 직접 맡거나
     * 다른 경로가 terminal을 확정한 뒤 부른다. 부르지 않아도 worker는 heartbeat 실패(lease·fence·terminal)나
     * NOT_ACCEPTING terminal로 스스로 끝난다.
     */
    public boolean release(UUID runId) {
        RunExecution execution = executions.get(runId);
        if (execution == null) {
            return false;
        }
        execution.stop(StopReason.FINISHED_ELSEWHERE);
        return true;
    }

    /** 이 인스턴스가 coordinator lease를 가졌는지. */
    public boolean isLeader() {
        return leader.get();
    }

    /** 지금 이 인스턴스가 쓰는 전역 슬롯 수. */
    public int slotsInUse() {
        return properties.maxConcurrentRuns() - slots.availablePermits();
    }

    String owner() {
        return owner;
    }

    Collection<RunExecution> executions() {
        return executions.values();
    }

    // ------------------------------------------------------------------
    // 주기 작업
    // ------------------------------------------------------------------

    /** coordinator lease 갱신 + 실행 중 run lease 갱신. lease를 잃은 run의 worker는 멈춘다. */
    void heartbeat() {
        boolean held;
        try {
            held = claims.acquireCoordinatorLease(owner, properties.leaseTtl());
        } catch (RuntimeException e) {
            // 갱신을 확인하지 못했으면 claim을 멈춘다. run lease 갱신은 아래에서 따로 계속한다.
            held = false;
            metrics.error("coordinator_lease");
            log.warn("coordinator lease renewal failed: cause={}", e.getClass().getSimpleName());
        }
        if (leader.getAndSet(held) != held) {
            if (held) {
                log.info("run coordinator lease acquired: owner={}", owner);
            } else {
                log.warn("run coordinator lease held by another instance; this instance stays passive: owner={}",
                        owner);
            }
        }
        for (RunExecution execution : executions.values()) {
            if (execution.stopReason() != null) {
                continue;
            }
            try {
                if (!claims.heartbeat(execution.claim(), owner, properties.leaseTtl())) {
                    metrics.heartbeatLost();
                    log.info("run lease no longer held: runId={}", execution.claim().runId());
                    execution.stop(StopReason.LEASE_LOST);
                }
            } catch (RuntimeException e) {
                // 한 번의 갱신 실패는 lease TTL이 흡수한다. 다음 주기에 다시 시도한다.
                metrics.error("heartbeat");
                log.warn("run heartbeat failed: runId={}, cause={}", execution.claim().runId(),
                        e.getClass().getSimpleName());
            }
        }
    }

    /** 빈 슬롯만큼 PENDING run을 claim해 worker를 시작한다. */
    void dispatch() {
        dispatchQueued.set(false);
        if (!running.get() || !leader.get()) {
            return;
        }
        // 주기 dispatch와 슬롯 반환 뒤 dispatch가 겹쳐 같은 DB 수로 각자 claim하면 전역 상한을 넘는다. 한 번에 하나만 돈다.
        // 기다리지 않는다(control thread를 묶으면 heartbeat·sweep이 늦어진다): 이미 돌고 있으면 다시 돌라고 표시만 하고,
        // 잡고 있는 쪽이 끝난 뒤 한 번 더 돈다.
        do {
            if (!dispatchLock.tryLock()) {
                redispatch.set(true);
                return;
            }
            try {
                redispatch.set(false);
                dispatchLocked();
            } finally {
                dispatchLock.unlock();
            }
        } while (redispatch.get() && running.get() && leader.get());
    }

    private void dispatchLocked() {
        int free = Math.min(slots.availablePermits(),
                properties.maxConcurrentRuns() - claims.countActiveExecutions());
        for (int i = 0; i < free && running.get(); i++) {
            if (!slots.tryAcquire()) {
                return;
            }
            Optional<ClaimedRun> claimed;
            try {
                claimed = claims.claimNext(owner, newFence(), properties.leaseTtl(),
                        properties.minDispatchRemaining());
            } catch (RuntimeException e) {
                slots.release();
                throw e;
            }
            if (claimed.isEmpty()) {
                slots.release();
                return;
            }
            launch(claimed.get());
        }
    }

    /** 요청 deadline이 지난 run을 timeout 실패·보상으로 끝낸다(PENDING 대기 중 포함). */
    void sweepDeadlines() {
        if (!running.get() || !leader.get()) {
            return;
        }
        List<UUID> expired = claims.findDeadlinePassed(DEADLINE_BATCH);
        for (UUID runId : expired) {
            // 한 run의 실패가 batch를 막지 않게 run별로 처리한다(같은 run이 매 주기 맨 앞에 와도 나머지는 진행).
            try {
                TerminalSettlement settlement = terminals.expireDeadline(runId);
                if (settlement.outcome() == TerminalSettlement.Outcome.COMMITTED) {
                    metrics.deadlineExpired();
                    log.info("run deadline expired: runId={}", runId);
                    claims.reserveRuntimeCleanup(runId, properties.runtimeCleanupDelay());
                    RunExecution execution = executions.get(runId);
                    if (execution != null) {
                        execution.stop(StopReason.FINISHED_ELSEWHERE);
                    }
                }
            } catch (RuntimeException e) {
                metrics.error("deadline");
                log.warn("deadline expiry failed: runId={}, cause={}", runId, e.getClass().getSimpleName());
            }
        }
    }

    /**
     * lease가 만료된 stale run을 같은 attempt·fence로 인수해 status 수습 worker를 띄운다. 살아 있는 로컬 worker가 있는 run
     * (heartbeat만 늦은 경우)은 건너뛴다. 로컬 permit이 없으면 다음 주기로 미룬다. 인수 횟수가 한도를 넘은 run은 저장
     * terminal 확인 한 번만 한다({@link Mode#FINAL_CHECK}). 이 작업만 비PENDING run의 worker를 띄우므로 같은 run의 로컬
     * worker가 겹치지 않는다(주기 작업은 자기와 겹쳐 돌지 않는다).
     */
    void recoverStale() {
        if (!running.get() || !leader.get()) {
            return;
        }
        RunCoordinatorProperties.Recovery policy = properties.recovery();
        for (UUID runId : claims.findStale(policy.batch())) {
            if (!running.get()) {
                return;
            }
            if (executions.containsKey(runId)) {
                continue;
            }
            if (!slots.tryAcquire()) {
                metrics.recovery("NO_PERMIT");
                return;
            }
            Optional<StaleRun> taken;
            try {
                taken = claims.takeOver(runId, owner, properties.leaseTtl());
            } catch (RuntimeException e) {
                slots.release();
                metrics.error("recovery");
                log.warn("stale run takeover failed: runId={}, cause={}", runId, e.getClass().getSimpleName());
                continue;
            }
            if (taken.isEmpty()) {
                // heartbeat 재개·terminal·취소·다른 인수자가 먼저였다.
                slots.release();
                metrics.recovery("NOT_TAKEN");
                continue;
            }
            StaleRun stale = taken.get();
            Mode mode = stale.recoveryCount() > policy.maxRecoveries() ? Mode.FINAL_CHECK : Mode.RECOVER;
            metrics.recovery(mode == Mode.RECOVER ? "TAKEN" : "LIMIT_EXCEEDED");
            log.info("stale run taken over: runId={}, attempt={}, recoveryCount={}, mode={}", runId,
                    stale.claim().attempt(), stale.recoveryCount(), mode);
            launch(stale.claim(), mode);
        }
    }

    private void launch(ClaimedRun claim) {
        metrics.claimed();
        log.info("run claimed: runId={}, attempt={}", claim.runId(), claim.attempt());
        launch(claim, Mode.DISPATCH);
    }

    private void launch(ClaimedRun claim, Mode mode) {
        RunExecution execution = new RunExecution(claim, mode, dependencies, this::finished);
        executions.put(claim.runId(), execution);
        try {
            workers.execute(execution);
        } catch (RejectedExecutionException e) {
            // 대기열이 유한하지 않으므로 pool이 종료될 때(stop과 겹친 claim)만 일어난다. 정상 run을 실패시키지 않고
            // claim도 되돌리지 않는다: lease를 만료시켜 회수(1.85) 대상으로 남긴다. run은 terminal까지 슬롯을 차지한다.
            executions.remove(claim.runId());
            slots.release();
            metrics.error("launch");
            log.warn("run worker not started (coordinator stopping): runId={}", claim.runId());
            try {
                claims.expireLease(claim, owner);
            } catch (RuntimeException expiry) {
                log.warn("lease expiry failed: runId={}, cause={}", claim.runId(), expiry.getClass().getSimpleName());
            }
        }
    }

    private void finished(RunExecution execution) {
        executions.remove(execution.claim().runId(), execution);
        slots.release();
        if (execution.mode() != Mode.DISPATCH) {
            metrics.recoveryFinished(execution.result());
        }
        // 반환한 슬롯을 다음 PENDING run이 poll 주기를 기다리지 않고 쓰게 한다.
        if (running.get() && dispatchQueued.compareAndSet(false, true)) {
            try {
                control.execute(guarded("dispatch", this::dispatch));
            } catch (RejectedExecutionException ignored) {
                dispatchQueued.set(false);
            }
        }
    }

    /**
     * 고정 크기 worker pool. 대기열 길이는 semaphore(전역 슬롯)가 상한을 지킨다 — permit은 run 하나당 하나이고 worker가
     * 끝날 때 반환되므로, 반환 직후 thread가 아직 비지 않았을 때 들어온 run은 거부되지 않고 잠깐 대기열에서 기다린다.
     */
    static ThreadPoolExecutor workerPool(int size) {
        return new ThreadPoolExecutor(size, size, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                threads("runCoordinator-worker-"));
    }

    private Runnable guarded(String stage, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                metrics.error(stage);
                log.warn("run coordinator {} failed: cause={}", stage, e.getClass().getSimpleName());
            }
        };
    }

    private static String newFence() {
        return "fence-" + UUID.randomUUID();
    }

    private static ThreadFactory threads(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
