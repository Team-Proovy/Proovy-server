package com.proovy.domain.run.stream;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * run SSE 연결 공용 thread pool. replay·live 전송, heartbeat, 주기 catch-up이 쓴다.
 * <p>
 * {@code Executor}·{@code ScheduledExecutorService}·{@code TaskScheduler} 타입 bean으로 노출하지 않는다. 그런 bean이 하나라도
 * 있으면 Spring Boot의 기본 {@code applicationTaskExecutor}(MVC async)·{@code taskScheduler}(@Scheduled)가 생성되지 않거나
 * @Scheduled가 이 pool을 골라 쓰게 된다(전역 기본 bean은 공통 설정이라 바꾸지 않는다).
 * <p>
 * 작업 queue는 연결 수에 비례한다: 연결마다 예약 작업 두 개(heartbeat·catch-up)와 실행 대기 전송 최대 한 개뿐이고,
 * 신호가 몰려도 연결당 전송 작업은 늘지 않는다({@link RunEventStream}).
 */
@Slf4j
public class RunSseScheduler implements AutoCloseable {

    private final ScheduledThreadPoolExecutor executor;

    public RunSseScheduler(int threads) {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "run-sse-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        executor = new ScheduledThreadPoolExecutor(threads, factory);
        // 연결이 닫히면 예약 작업을 취소한다. 취소된 작업이 queue에 남지 않게 한다.
        executor.setRemoveOnCancelPolicy(true);
    }

    public void execute(Runnable task) {
        executor.execute(guarded(task));
    }

    public ScheduledFuture<?> every(Duration period, Runnable task) {
        long millis = period.toMillis();
        return executor.scheduleWithFixedDelay(guarded(task), millis, millis, TimeUnit.MILLISECONDS);
    }

    /** 반복 작업이 예외로 멈추지 않게 한다. 연결 오류는 각 연결이 스스로 닫는다. */
    private static Runnable guarded(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.warn("run SSE task failed: {}", e.getClass().getSimpleName());
            }
        };
    }

    int queuedTasks() {
        return executor.getQueue().size();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
