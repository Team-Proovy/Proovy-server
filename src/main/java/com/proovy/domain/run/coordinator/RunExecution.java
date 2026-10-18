package com.proovy.domain.run.coordinator;

import com.proovy.domain.run.brain.InternalRunContractViolation;
import com.proovy.domain.run.brain.InternalRunRequest;
import com.proovy.domain.run.brain.RunStatusQuery;
import com.proovy.domain.run.brain.RunStatusResponse;
import com.proovy.domain.run.brain.SourceEvent;
import com.proovy.domain.run.brain.StoredTerminal;
import com.proovy.domain.run.brain.StoredTerminalType;
import com.proovy.domain.run.entity.AiRun;
import com.proovy.domain.run.entity.AiRunStatus;
import com.proovy.domain.run.entity.RunFailureCode;
import com.proovy.domain.run.event.ActivityAppendResult;
import com.proovy.domain.run.event.RunEventStore;
import com.proovy.domain.run.repository.AiRunRepository;
import com.proovy.domain.run.snapshot.RunAssetAccess;
import com.proovy.domain.run.snapshot.RunSnapshotIntegrityException;
import com.proovy.domain.run.snapshot.RunSnapshotRestorer;
import com.proovy.domain.run.terminal.RunTerminalService;
import com.proovy.domain.run.terminal.TerminalSettlement;
import com.proovy.global.infra.brain.BrainCallException;
import com.proovy.global.infra.brain.BrainFailureKind;
import com.proovy.global.infra.brain.BrainRunClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import reactor.core.publisher.Sinks;

/**
 * claim한 run 하나의 Brain 위임(worker thread 하나, 전역 슬롯 하나). 브라우저 연결과 무관하다.
 * <h2>규칙</h2>
 * <ul>
 *   <li>요청은 {@link RunSnapshotRestorer#delivery}로 저장 snapshot·run 행 attempt·fence·누적 usage에서만 만든다.
 *       재전달도 <b>같은 요청(같은 attempt·fence)</b>이라 Brain은 미시작이면 START, 실행 중이면 OBSERVE, 끝났으면
 *       RETURN_TERMINAL로만 답한다. 새 attempt(새 graph epoch)를 만들지 않으므로 소실된 graph를 다시 시작하지 않는다.</li>
 *   <li>stream 소비 중 transaction·row lock이 없다. Activity는 {@link RunEventStore#appendActivity}, terminal은
 *       {@link RunTerminalService#settle}의 짧은 자기 transaction이다.</li>
 *   <li>stream이 terminal 없이 끝나면(transport 종료) 성공이 아니다 — client가 실패 kind로 알리고 status로 수습한다.</li>
 *   <li>재전달·조회는 {@link RunCoordinatorProperties.Redelivery} 횟수와 run의 고정 {@code request_deadline_at} 안에서만
 *       한다. deadline이 지나면 {@link RunTerminalService#expireDeadline}(timeout)이다.</li>
 * </ul>
 * <h2>실패 kind (1.76 {@link BrainFailureKind})</h2>
 * <ul>
 *   <li>재전송 가능(연결 실패·capacity·credential): RETRYING 표시 → 지수 backoff → 같은 요청.</li>
 *   <li>status로 수습(단절·timeout·5xx·terminal 없는 종료): 같은 attempt·fence로 status. 같은 epoch executing이면 같은
 *       요청 재전달(OBSERVE), terminal이면 저장 terminal을 {@link RunTerminalService#settle(StoredTerminal)}, fingerprint
 *       불일치는 실패. absent는 Brain이 그 요청을 시작한 증거(이 worker가 받은 frame·저장된 이 attempt의 source event)가
 *       없을 때만 미시작으로 보고 같은 요청을 보낸다. 증거가 있으면 실행 소실이라 재전송하지 않고 실패·보상한다(새 graph 없음).
 *       조회 한도 소진도 실패·보상이다.</li>
 *   <li>영구(계약·인증·contention·binding): {@code failByServer(INTERNAL_ERROR)}.</li>
 *   <li>stream 중 일시 DB 오류(연결·timeout·직렬화, transaction 시작 시 연결 획득 실패): 같은 재전달 한도 안에서 backoff 뒤 status 수습. 그 밖의 예외와
 *       수습 중 DB 실패는 LOCAL_ERROR(lease 만료 → 회수 1.85·deadline sweep)다.</li>
 * </ul>
 * <h2>stale 회수 (1.85, {@link Mode#RECOVER})</h2>
 * lease가 만료된 run을 같은 attempt·fence로 인수한 worker는 stream이 아니라 status부터 본다. 저장 terminal은 새 provider
 * 실행 없이 확정하고(snapshot 복원도 하지 않는다), 살아 있는 같은 epoch 실행이면 같은 요청으로 다시 붙는다. 재전달 한도는
 * run 행 {@code retry_count}부터 이어서 세므로 재시작·인수가 예산을 초기화하지 않는다. 인수 한도를 넘은 run
 * ({@link Mode#FINAL_CHECK})은 status 한 번으로 저장 terminal만 확인하고, 없으면 재전송 없이 실패·보상한다.
 */
@Slf4j
final class RunExecution implements Runnable {

    /** worker가 끝난 이유. 슬롯은 모든 경우에 반환된다. */
    enum Result {
        /** 이 worker가 terminal을 확정했거나 이미 확정된 것을 확인했다. */
        SETTLED,
        /** 이 attempt·fence가 더 이상 현재가 아니다(다른 terminal·취소·fence 교체). run은 다른 경로가 끝낸다. */
        OBSOLETE,
        /** 종료·lease 상실로 멈췄다. run은 진행 중 상태로 남고 stale 회수(1.85)·deadline sweep 대상이다. */
        STOPPED,
        /** DB 오류 등 예상하지 못한 실패. lease를 만료시켜 회수 대상으로 넘긴다. */
        LOCAL_ERROR
    }

    /** worker가 run을 맡은 경로. */
    enum Mode {
        /** PENDING claim 직후: 첫 전달부터 stream을 연다. */
        DISPATCH,
        /** stale run 인수: status부터 보고 필요할 때만 같은 요청을 보낸다. */
        RECOVER,
        /** 인수 한도를 넘은 run: status 한 번으로 저장 terminal만 확인하고, 없으면 실패 수습한다. */
        FINAL_CHECK
    }

    enum StopReason {
        SHUTDOWN,
        LEASE_LOST,
        FINISHED_ELSEWHERE
    }

    record Dependencies(
            BrainRunClient brain,
            RunEventStore events,
            RunTerminalService terminals,
            RunClaimStore claims,
            AiRunRepository runs,
            RunAssetAccessProvider access,
            RunCoordinatorProperties properties,
            RunCoordinatorMetrics metrics,
            String owner,
            Clock clock) {
    }

    private static final int STREAM_PREFETCH = 8;

    private final ClaimedRun claim;
    private final Mode mode;
    private final Dependencies deps;
    private final Consumer<RunExecution> onFinish;
    private final CountDownLatch stopLatch = new CountDownLatch(1);
    private final Sinks.Empty<Void> stopSink = Sinks.empty();
    private volatile StopReason stopReason;
    private volatile Result result;
    private int resends;
    private AiRun run;
    private boolean retrying;
    /** 이 worker가 Brain stream frame을 하나라도 받았다(Brain이 이 요청을 등록·시작했다). */
    private boolean brainSeen;

    RunExecution(ClaimedRun claim, Dependencies deps, Consumer<RunExecution> onFinish) {
        this(claim, Mode.DISPATCH, deps, onFinish);
    }

    RunExecution(ClaimedRun claim, Mode mode, Dependencies deps, Consumer<RunExecution> onFinish) {
        this.claim = claim;
        this.mode = mode;
        this.deps = deps;
        this.onFinish = onFinish;
    }

    ClaimedRun claim() {
        return claim;
    }

    Result result() {
        return result;
    }

    Mode mode() {
        return mode;
    }

    /** 멈춤 신호: 열린 stream 구독을 끊고(Brain 실행은 계속된다) backoff 대기를 깨운다. */
    void stop(StopReason reason) {
        if (stopReason == null) {
            stopReason = reason;
        }
        stopSink.tryEmitEmpty();
        stopLatch.countDown();
    }

    StopReason stopReason() {
        return stopReason;
    }

    @Override
    public void run() {
        Result outcome = Result.LOCAL_ERROR;
        try {
            outcome = execute();
        } catch (RuntimeException e) {
            // 예외 message에는 SQL·값이 들어갈 수 있어 class 이름만 남긴다.
            log.error("run execution failed locally: runId={}, attempt={}, cause={}", claim.runId(), claim.attempt(),
                    e.getClass().getSimpleName());
            deps.metrics().error("execution");
            outcome = Result.LOCAL_ERROR;
        } finally {
            result = outcome;
            if (outcome == Result.LOCAL_ERROR || (outcome == Result.STOPPED && stopReason == StopReason.SHUTDOWN)) {
                expireLease();
            }
            deps.metrics().execution(outcome);
            log.info("run execution finished: runId={}, attempt={}, mode={}, result={}, resends={}, stop={}",
                    claim.runId(), claim.attempt(), mode, outcome, resends, stopReason);
            onFinish.accept(this);
        }
    }

    private Result execute() {
        run = deps.runs().findById(claim.runId()).orElse(null);
        if (run == null || run.getAttempt() != claim.attempt() || !claim.fenceToken().equals(run.getFenceToken())
                || (run.getStatus() != AiRunStatus.RUNNING && run.getStatus() != AiRunStatus.RETRYING)) {
            log.info("run changed before execution: runId={}, mode={}", claim.runId(), mode);
            return Result.OBSOLETE;
        }
        // 재전달 예산은 run 행 retry_count(단조)부터 이어서 센다 — 재시작·인수가 한도를 초기화하지 않는다.
        resends = run.getRetryCount();
        retrying = run.getStatus() == AiRunStatus.RETRYING;
        Instant deadline = run.getRequestDeadlineAt();
        if (mode != Mode.DISPATCH) {
            // 인수한 run은 이전 전달이 Brain에 닿았는지 모른다: 보내기 전에 status로 확인한다(실행을 시작하지 않는 조회).
            Result recovered = reconcile(deadline);
            if (recovered != null) {
                return recovered;
            }
        }
        InternalRunRequest request = null;
        while (true) {
            if (stopped()) {
                return Result.STOPPED;
            }
            try {
                // 첫 전달은 저장 snapshot에서 복원한다. grant·read URL은 전달마다 새로 받는다(재전달에 이전 값을 쓰지 않는다).
                request = request == null ? restore() : withFreshAccess(request);
            } catch (RunSnapshotIntegrityException | InternalRunContractViolation e) {
                // 저장 snapshot을 그대로 복원할 수 없다. 다시 만들지 않고 실패·보상으로 끝낸다(run-snapshot-v1).
                log.error("run snapshot cannot be restored: runId={}, cause={}", claim.runId(),
                        e.getClass().getSimpleName());
                return failByServer(RunFailureCode.INTERNAL_ERROR, "snapshot_integrity");
            }
            Result step;
            try {
                step = consume(request);
            } catch (BrainCallException failure) {
                step = afterFailure(failure, deadline);
            } catch (TransientDataAccessException | RecoverableDataAccessException
                     | DataAccessResourceFailureException | CannotCreateTransactionException local) {
                // Activity·terminal 저장 중 일시 DB 오류: 이 stream을 끊고 같은 attempt로 status 수습에 합류한다.
                // Brain은 같은 요청 재전달에 OBSERVE·RETURN_TERMINAL로 답하고 Activity는 source ID로 dedup된다.
                step = afterLocalFailure(local, deadline);
            }
            if (step != null) {
                return step;
            }
        }
    }

    private InternalRunRequest restore() {
        return RunSnapshotRestorer.delivery(run, deps.access().accessFor(run, claim));
    }

    private InternalRunRequest withFreshAccess(InternalRunRequest request) {
        RunAssetAccess access = deps.access().accessFor(run, claim);
        return request.withAssetAccess(access.assetAccessGrant(), access.viewerReadUrl(),
                access.turnAttachmentReadUrls());
    }

    private Result afterLocalFailure(RuntimeException failure, Instant deadline) {
        deps.metrics().error("transient_db");
        log.warn("transient storage failure during stream: runId={}, attempt={}, cause={}", claim.runId(),
                claim.attempt(), failure.getClass().getSimpleName());
        RunCoordinatorProperties.Redelivery policy = deps.properties().redelivery();
        if (resends >= policy.maxResends()) {
            // DB를 쓸 수 없으니 실패 확정도 못 할 수 있다. 예외가 나면 LOCAL_ERROR로 회수(1.85)·deadline sweep에 넘긴다.
            return exhausted(deadline, "resends_TRANSIENT_DB");
        }
        resends++;
        if (await(policy.backoff(resends))) {
            return Result.STOPPED;
        }
        return reconcile(deadline);
    }

    /**
     * stream 하나를 끝까지 소비한다. terminal을 처리하면 그 결과, 멈추면 STOPPED, stream이 쓸모없어지면 OBSOLETE.
     * transport 실패는 {@link BrainCallException}으로 던진다(terminal 없는 종료 포함).
     */
    private Result consume(InternalRunRequest request) {
        try (Stream<SourceEvent> stream = deps.brain().stream(request)
                .takeUntilOther(stopSink.asMono())
                .toStream(STREAM_PREFETCH)) {
            Iterator<SourceEvent> events = stream.iterator();
            while (events.hasNext()) {
                SourceEvent event = events.next();
                brainSeen = true;
                if (retrying) {
                    retrying = false;
                    deps.claims().markRunning(claim, deps.owner());
                }
                if (event.isTerminal()) {
                    return settled(deps.terminals().settle(event));
                }
                ActivityAppendResult appended = deps.events().appendActivity(event);
                if (appended.isStreamObsolete()) {
                    log.info("stream obsolete: runId={}, outcome={}", claim.runId(), appended.outcome());
                    return Result.OBSOLETE;
                }
                if (!appended.isStored() && appended.outcome() != ActivityAppendResult.Outcome.NOT_ACCEPTING) {
                    log.warn("source activity not stored: runId={}, sourceEventId={}, outcome={}", claim.runId(),
                            event.sourceEventId(), appended.outcome());
                }
            }
        }
        // takeUntilOther로 끊긴 경우만 여기 온다. 끊기지 않았는데 끝났다면 client가 이미 오류를 냈다.
        return Result.STOPPED;
    }

    private Result afterFailure(BrainCallException failure, Instant deadline) {
        if (stopped()) {
            return Result.STOPPED;
        }
        BrainFailureKind kind = failure.kind();
        log.info("brain stream failed: runId={}, attempt={}, kind={}, httpStatus={}", claim.runId(), claim.attempt(),
                kind, failure.httpStatus());
        if (kind.isSafeToResend()) {
            return resend(kind.name(), deadline);
        }
        if (kind.isRecoverableByStatusQuery()) {
            return reconcile(deadline);
        }
        if (kind == BrainFailureKind.REQUEST_ID_BOUND_TO_OTHER_RUN) {
            log.error("request_id bound to another Brain run (server bug): runId={}", claim.runId());
        }
        return failByServer(RunFailureCode.INTERNAL_ERROR, kind.name());
    }

    /** 같은 요청 재전달 준비. null이면 다시 보낸다. */
    private Result resend(String reason, Instant deadline) {
        RunCoordinatorProperties.Redelivery policy = deps.properties().redelivery();
        if (resends >= policy.maxResends()) {
            return exhausted(deadline, "resends_" + reason);
        }
        Duration delay = policy.backoff(resends + 1);
        if (!now().plus(delay).isBefore(deadline)) {
            return exhausted(deadline, "deadline_" + reason);
        }
        if (!deps.claims().markRetrying(claim, deps.owner())) {
            log.info("run no longer owned before redelivery: runId={}", claim.runId());
            return Result.OBSOLETE;
        }
        resends++;
        retrying = true;
        deps.metrics().redelivery(reason);
        return await(delay) ? Result.STOPPED : null;
    }

    /**
     * status-recoverable 실패·stale 인수의 수습. 같은 attempt·fence로만 조회하고 실행을 시작하지 않는다. null이면 같은
     * 요청을 다시 보낸다. 대조 fingerprint는 run 행의 {@code request_fingerprint}다(snapshot을 다시 만들지 않는다).
     */
    private Result reconcile(Instant deadline) {
        RunCoordinatorProperties.Redelivery policy = deps.properties().redelivery();
        for (int check = 1; ; check++) {
            if (stopped()) {
                return Result.STOPPED;
            }
            if (!now().isBefore(deadline)) {
                return expire();
            }
            RunStatusResponse status;
            try {
                status = deps.brain().status(RunStatusQuery.of(claim.runId().toString(), claim.attempt(),
                        claim.fenceToken())).block();
            } catch (BrainCallException failure) {
                deps.metrics().statusCheck("FAILED_" + failure.kind().name());
                if (check >= policy.maxStatusChecks()) {
                    return exhausted(deadline, "status_" + failure.kind().name());
                }
                if (await(policy.backoff(check))) {
                    return Result.STOPPED;
                }
                continue;
            }
            if (status == null) {
                throw new IllegalStateException("status returned no response");
            }
            deps.metrics().statusCheck(status.state().name());
            log.info("brain status: runId={}, attempt={}, mode={}, state={}", claim.runId(), claim.attempt(), mode,
                    status.state());
            switch (status.state()) {
                case ABSENT -> {
                    // Brain record는 영속이라 absent = 이 요청이 등록된 적 없다. 단 Brain이 시작했던 증거가 있으면
                    // record가 사라진 실행 소실이다: 같은 요청을 보내면 새 graph가 시작되므로 재전송하지 않는다.
                    if (brainSeen || deps.claims().hasBrainEvents(claim)) {
                        deps.metrics().executionLost();
                        return failByServer(RunFailureCode.PROVIDER_ERROR, "execution_lost");
                    }
                    if (mode == Mode.FINAL_CHECK) {
                        return failByServer(RunFailureCode.PROVIDER_ERROR, "recoveries_exhausted");
                    }
                    // 명확한 미시작: 같은 요청이 첫 START다(#215).
                    return resend("STATUS_ABSENT", deadline);
                }
                case EXECUTING -> {
                    if (!matchesRun(status)) {
                        return failByServer(RunFailureCode.INTERNAL_ERROR, "status_fingerprint_mismatch");
                    }
                    if (!sameEpoch(status)) {
                        log.warn("brain record follows another epoch: runId={}, attempt={}, brainAttempt={}",
                                claim.runId(), claim.attempt(), status.attempt());
                        return Result.OBSOLETE;
                    }
                    if (mode == Mode.FINAL_CHECK) {
                        // 인수 한도를 넘었다: 살아 있다는 응답만으로 더 관찰하지 않는다(늦은 terminal은 1.82가 거부한다).
                        return failByServer(RunFailureCode.PROVIDER_ERROR, "recoveries_exhausted");
                    }
                    // 같은 실행에 다시 붙는다(OBSERVE). 새 실행이 아니다.
                    return resend("STATUS_EXECUTING", deadline);
                }
                case TERMINAL -> {
                    if (!matchesRun(status)) {
                        return failByServer(RunFailureCode.INTERNAL_ERROR, "status_fingerprint_mismatch");
                    }
                    StoredTerminal terminal = status.terminal();
                    if (terminal.type() == StoredTerminalType.RUN_CANCELED) {
                        // 취소 terminal은 취소 경로(1.84)가 확정한다.
                        log.info("brain stored run.canceled: runId={}", claim.runId());
                        return Result.OBSOLETE;
                    }
                    return settled(deps.terminals().settle(terminal));
                }
                case CANCELING -> {
                    return Result.OBSOLETE;
                }
                default -> throw new IllegalStateException("unknown record state");
            }
        }
    }

    private boolean matchesRun(RunStatusResponse status) {
        return run.getRequestFingerprint().equals(status.requestFingerprint());
    }

    private boolean sameEpoch(RunStatusResponse status) {
        return status.attempt() != null && status.attempt() == claim.attempt()
                && claim.fenceToken().equals(status.fenceToken());
    }

    private Result settled(TerminalSettlement settlement) {
        return switch (settlement.outcome()) {
            case COMMITTED, DUPLICATE -> {
                reserveCleanup();
                yield Result.SETTLED;
            }
            case DEADLINE_PASSED -> expire();
            case REJECTED, SCOPE_NOT_READY -> {
                // 공개할 결과를 검증하지 못했다. payload는 남기지 않는다(1.82 인계).
                log.warn("brain terminal not accepted: runId={}, outcome={}, rejection={}, field={}", claim.runId(),
                        settlement.outcome(), settlement.rejection(), settlement.rejectionField());
                yield failByServer(RunFailureCode.INTERNAL_ERROR, "terminal_" + settlement.outcome().name());
            }
            default -> {
                log.info("terminal not applied: runId={}, outcome={}", claim.runId(), settlement.outcome());
                yield Result.OBSOLETE;
            }
        };
    }

    private Result exhausted(Instant deadline, String reason) {
        if (!now().isBefore(deadline)) {
            return expire();
        }
        return failByServer(RunFailureCode.PROVIDER_ERROR, reason);
    }

    private Result expire() {
        TerminalSettlement settlement = deps.terminals().expireDeadline(claim.runId());
        if (settlement.isSettled()) {
            deps.metrics().deadlineExpired();
            reserveCleanup();
            return Result.SETTLED;
        }
        return Result.OBSOLETE;
    }

    private Result failByServer(RunFailureCode code, String reason) {
        TerminalSettlement settlement = deps.terminals().failByServer(claim.runId(), claim.attempt(),
                claim.fenceToken(), code, true);
        log.warn("server failure: runId={}, attempt={}, code={}, reason={}, outcome={}", claim.runId(),
                claim.attempt(), code.wireValue(), reason, settlement.outcome());
        if (settlement.isSettled()) {
            deps.metrics().serverFailure(code.wireValue());
            reserveCleanup();
            return Result.SETTLED;
        }
        return Result.OBSOLETE;
    }

    private void reserveCleanup() {
        try {
            deps.claims().reserveRuntimeCleanup(claim.runId(), deps.properties().runtimeCleanupDelay());
        } catch (RuntimeException e) {
            log.warn("runtime cleanup reservation failed: runId={}, cause={}", claim.runId(),
                    e.getClass().getSimpleName());
            deps.metrics().error("cleanup_reservation");
        }
    }

    private void expireLease() {
        try {
            deps.claims().expireLease(claim, deps.owner());
        } catch (RuntimeException e) {
            log.warn("lease expiry failed: runId={}, cause={}", claim.runId(), e.getClass().getSimpleName());
        }
    }

    /** true면 기다리는 중 멈춤 신호를 받았다. */
    private boolean await(Duration delay) {
        try {
            return stopLatch.await(delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop(StopReason.SHUTDOWN);
            return true;
        }
    }

    private boolean stopped() {
        return stopReason != null;
    }

    private Instant now() {
        return deps.clock().instant();
    }
}
