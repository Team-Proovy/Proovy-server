package com.proovy.domain.run.stream;

import com.proovy.domain.run.event.RunEventSignals;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/**
 * 브라우저 연결 하나의 durable replay와 live 합류.
 *
 * <h2>무유실·무중복</h2>
 * <ol>
 *   <li>{@link #start()}는 <b>신호 구독을 먼저</b> 걸고 그다음 cursor 이후를 DB에서 읽는다. 구독 전 commit은 첫 catch-up이,
 *       구독 뒤 commit은 신호가 깨운 catch-up이 읽는다.</li>
 *   <li>보낼 frame은 항상 {@code seq > cursor}를 DB에서 seq 순으로 읽는다(1.80: commit 순서 = seq 순서라 아직 commit되지 않은
 *       앞 seq를 건너뛰지 않는다). 보낸 frame의 seq로 cursor를 올리므로 replay와 live 경계에서 같은 seq를 두 번 보내지 않는다.</li>
 *   <li>신호는 깨우기 힌트일 뿐이다. 신호가 유실되어도 {@code catchUpInterval}마다 DB를 다시 보므로 그 주기 안에 복구된다.</li>
 * </ol>
 *
 * <h2>연결당 전송 하나</h2>
 * 연결의 모든 쓰기(frame 전송 drain과 heartbeat)는 {@code draining} 소유권 하나를 가진 쪽만 한다. 그래서 연결마다 쓰는 thread는
 * 동시에 최대 하나이고, 느린 브라우저는 pool thread 하나만 붙잡는다(heartbeat가 다른 thread에서 쓰기를 기다리지 않는다 — 전송 중이면
 * heartbeat는 필요 없어 건너뛴다). 전송 중 들어온 신호는 {@code pending} 표시 하나로 합쳐지고 끝나면 한 번 더 읽는다. 그래서 신호가
 * 몰리거나 브라우저가 느려도 서버에 연결별 frame queue가 쌓이지 않고, 신호 listener(append commit thread)는 기다리지 않는다.
 *
 * <h2>종료</h2>
 * terminal frame을 보내면(또는 terminal run의 cursor가 이미 끝이면) 연결을 정상 종료한다. 브라우저 단절·timeout은
 * {@link #close()} — 구독과 예약 작업만 해제하고 run 실행·상태는 건드리지 않는다. 복원·조회 실패는 오류 종료
 * ({@link RunEventSink#fail})라 브라우저는 terminal 없이 끝난 stream으로 보고 backoff 뒤 재접속한다.
 */
@Slf4j
final class RunEventStream {

    private final UUID runId;
    private final RunFrameSource source;
    private final RunEventSink sink;
    private final RunEventSignals signals;
    private final RunSseScheduler scheduler;
    private final RunSseProperties properties;
    private final Runnable onClose;

    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean pending;
    /** 마지막으로 보낸 frame의 seq. drain thread만 바꾼다. */
    private volatile long cursor;

    private RunEventSignals.Subscription subscription;
    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> catchUpTask;

    /**
     * @param afterSeq 이 seq 다음부터 보낸다(cursor 없는 연결은 0)
     * @param onClose  연결이 닫힐 때 한 번 불린다(열린 연결 수 집계)
     */
    RunEventStream(UUID runId, long afterSeq, RunFrameSource source, RunEventSink sink, RunEventSignals signals,
                   RunSseScheduler scheduler, RunSseProperties properties, Runnable onClose) {
        this.runId = runId;
        this.cursor = afterSeq;
        this.source = source;
        this.sink = sink;
        this.signals = signals;
        this.scheduler = scheduler;
        this.properties = properties;
        this.onClose = onClose;
    }

    void start() {
        synchronized (this) {
            subscription = signals.subscribe(runId, (id, lastSeq) -> {
                if (lastSeq > cursor) {
                    wake();
                }
            });
            heartbeatTask = scheduler.every(properties.heartbeat(), this::heartbeat);
            catchUpTask = scheduler.every(properties.catchUpInterval(), this::wake);
        }
        if (closed.get()) {
            // start 전에 연결이 이미 닫혔다(onCompletion이 먼저 옴)
            release();
            return;
        }
        wake();
    }

    /** 신호·주기 catch-up. 빨리 반환한다(전송은 pool thread에서). */
    void wake() {
        if (closed.get()) {
            return;
        }
        pending = true;
        if (draining.compareAndSet(false, true)) {
            scheduler.execute(this::drain);
        }
    }

    private void drain() {
        try {
            while (pending && !closed.get()) {
                pending = false;
                pumpOnce();
            }
        } catch (IOException e) {
            log.debug("run SSE client gone: runId={}", runId);
            close();
        } catch (RuntimeException e) {
            // 같은 seq에서 반복될 수 있는 복원·조회 실패: 원인을 남기고 오류로 끝낸다(정상 종료로 보이면 즉시 재접속 루프가 된다)
            log.error("run SSE stream failed: runId={}, cursor={}", runId, cursor, e);
            if (close()) {
                sink.fail(e);
            }
        } finally {
            yieldWriter();
        }
    }

    /** 쓰기 소유권을 놓는다. 해제와 새 신호 사이 경합: 표시가 남아 있으면 다시 맡는다. */
    private void yieldWriter() {
        draining.set(false);
        if (pending && !closed.get() && draining.compareAndSet(false, true)) {
            scheduler.execute(this::drain);
        }
    }

    private void pumpOnce() throws IOException {
        // 상태를 event보다 먼저 읽는다: 여기서 terminal이면 terminal event도 이미 commit되어 아래 조회에 보인다.
        Optional<RunFrameSource.Progress> progress = source.progress(runId);
        if (progress.isEmpty()) {
            finish();
            return;
        }
        if (progress.get().lastSeq() > cursor) {
            while (!closed.get()) {
                List<OutboundFrame> frames = source.after(runId, cursor, properties.batchSize());
                for (OutboundFrame frame : frames) {
                    if (frame.seq() <= cursor) {
                        continue;
                    }
                    write(frame.sse());
                    cursor = frame.seq();
                    if (frame.terminal()) {
                        finish();
                        return;
                    }
                }
                if (frames.size() < properties.batchSize()) {
                    break;
                }
            }
        }
        if (progress.get().terminal()) {
            // terminal run에서 cursor가 이미 terminal 뒤다(0 frame 재접속)
            finish();
        }
    }

    /** 전송 중이면 건너뛴다(frame이 이미 흐르고 있고, 쓰기를 기다리느라 두 번째 thread를 붙잡지 않는다). */
    private void heartbeat() {
        if (closed.get() || !draining.compareAndSet(false, true)) {
            return;
        }
        try {
            write(OutboundFrame.HEARTBEAT);
        } catch (IOException e) {
            close();
        } finally {
            yieldWriter();
        }
    }

    /** {@code draining} 소유자만 부른다. */
    private void write(String block) throws IOException {
        if (closed.get()) {
            throw new IOException("stream closed");
        }
        sink.write(block);
    }

    private void finish() {
        if (close()) {
            sink.complete();
        }
    }

    /**
     * 구독·예약 작업을 해제한다. 여러 번 불러도 된다. 실행 취소가 아니다.
     *
     * @return 이번 호출이 닫았으면 true
     */
    boolean close() {
        if (!closed.compareAndSet(false, true)) {
            return false;
        }
        release();
        onClose.run();
        return true;
    }

    private synchronized void release() {
        if (subscription != null) {
            subscription.close();
        }
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
        if (catchUpTask != null) {
            catchUpTask.cancel(false);
        }
    }

    boolean isClosed() {
        return closed.get();
    }

    long cursor() {
        return cursor;
    }
}
