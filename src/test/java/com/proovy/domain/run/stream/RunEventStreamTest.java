package com.proovy.domain.run.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.proovy.domain.run.event.RunEventSignals;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link RunEventStream}의 replay/live 합류를 DB 없이 검사한다: 구독 먼저·cursor catch-up, 신호 유실 복구, 경계 append,
 * 느린 구독(신호 병합·listener 비차단), 종료·단절, heartbeat. 실제 HTTP·Postgres 경로는 {@code BrowserRunSsePostgresTest}.
 */
class RunEventStreamTest {

    private static final UUID RUN = UUID.fromString("5e186280-06ec-5099-8087-8de875b2b83b");
    private static final RunSseProperties FAST = new RunSseProperties(Duration.ofHours(1), Duration.ofHours(1),
            Duration.ofMinutes(1), 3, 4);

    private final RunSseScheduler scheduler = new RunSseScheduler(4);
    private final FakeSignals signals = new FakeSignals();
    private final FakeSource source = new FakeSource();
    private final AtomicInteger closed = new AtomicInteger();

    @AfterEach
    void shutdown() {
        scheduler.close();
    }

    @Test
    @DisplayName("cursor 없는 연결은 저장 event 전체를 보내고, 신호마다 cursor 이후만 이어 보내며 terminal 뒤 닫는다")
    void replayThenLive() throws Exception {
        source.append(5);
        RecordingSink sink = new RecordingSink();
        RunEventStream stream = stream(0, sink, FAST);
        stream.start();
        await(() -> sink.seqs().size() == 5);

        source.append(2);
        signals.fire(RUN, 7);
        await(() -> sink.seqs().size() == 7);
        source.appendTerminal();
        signals.fire(RUN, 8);
        await(sink::completed);

        assertThat(sink.seqs()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        assertThat(stream.isClosed()).isTrue();
        assertThat(signals.listeners()).isZero();
        assertThat(closed).hasValue(1);
    }

    @Test
    @DisplayName("cursor가 있으면 그 다음 seq부터 보낸다")
    void resumesAfterCursor() throws Exception {
        source.append(6);
        source.appendTerminal();
        RecordingSink sink = new RecordingSink();
        stream(4, sink, FAST).start();
        await(sink::completed);
        assertThat(sink.seqs()).containsExactly(5L, 6L, 7L);
    }

    @Test
    @DisplayName("terminal run에서 cursor가 이미 끝이면 0 frame으로 닫는다")
    void terminalAtCursorClosesWithoutFrames() throws Exception {
        source.append(3);
        source.appendTerminal();
        RecordingSink sink = new RecordingSink();
        stream(4, sink, FAST).start();
        await(sink::completed);
        assertThat(sink.blocks()).isEmpty();
    }

    @Test
    @DisplayName("신호가 유실되어도 주기 catch-up이 DB에서 복구한다")
    void lostSignalRecoveredByCatchUp() throws Exception {
        RunSseProperties polling = new RunSseProperties(Duration.ofHours(1), Duration.ofMillis(50),
                Duration.ofMinutes(1), 100, 4);
        source.append(1);
        RecordingSink sink = new RecordingSink();
        stream(0, sink, polling).start();
        await(() -> sink.seqs().size() == 1);

        source.append(3);
        source.appendTerminal(); // 신호 없음
        await(sink::completed);
        assertThat(sink.seqs()).containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    @DisplayName("구독과 첫 catch-up 사이에 commit된 event도, catch-up 도중 commit된 event도 정확히 한 번 보낸다")
    void boundaryAppendIsNeitherLostNorDuplicated() throws Exception {
        source.append(2);
        // 첫 조회 직후(replay 경계)에 append + 신호: 구독은 이미 걸려 있다
        source.onFirstRead = () -> {
            source.append(2);
            signals.fire(RUN, 4);
        };
        RecordingSink sink = new RecordingSink();
        stream(0, sink, FAST).start();
        await(() -> sink.seqs().size() == 4);
        // 이미 보낸 seq의 늦은 신호는 아무것도 다시 보내지 않는다
        signals.fire(RUN, 2);
        signals.fire(RUN, 4);
        source.appendTerminal();
        signals.fire(RUN, 5);
        await(sink::completed);
        assertThat(sink.seqs()).containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    @DisplayName("느린 구독: 쓰기가 막혀도 신호 listener는 즉시 반환하고 신호는 합쳐져 서버 queue가 쌓이지 않는다")
    void slowSubscriberDoesNotBlockSignalsOrQueue() throws Exception {
        source.append(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(1);
        RecordingSink slow = new RecordingSink() {
            @Override
            public void write(String block) throws IOException {
                blocked.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                super.write(block);
            }
        };
        RunEventStream slowStream = stream(0, slow, FAST);
        slowStream.start();
        assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();

        // 다른 연결은 느린 연결과 무관하게 진행한다
        FakeSource otherSource = new FakeSource();
        otherSource.append(2);
        otherSource.appendTerminal();
        RecordingSink other = new RecordingSink();
        new RunEventStream(UUID.randomUUID(), 0, otherSource, other, signals, scheduler, FAST, () -> { }).start();
        await(other::completed);

        int readsBefore = source.reads.get();
        long started = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            source.append(1);
            signals.fire(RUN, source.lastSeq());
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(elapsedMillis).as("listener는 전송을 기다리지 않는다").isLessThan(2000);
        assertThat(scheduler.queuedTasks()).as("신호 1000개가 전송 작업 1000개가 되지 않는다").isLessThan(10);
        assertThat(source.reads.get()).as("막힌 동안 DB도 다시 읽지 않는다").isEqualTo(readsBefore);

        source.appendTerminal();
        signals.fire(RUN, source.lastSeq());
        release.countDown();
        await(slow::completed);
        List<Long> expected = new ArrayList<>();
        for (long seq = 1; seq <= 1002; seq++) {
            expected.add(seq);
        }
        assertThat(slow.seqs()).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("느린 연결은 pool thread 하나만 붙잡는다: 쓰기가 막힌 동안 heartbeat는 건너뛰고 thread 2개 pool의 다른 연결이 진행한다")
    void slowConnectionHoldsOneThread() throws Exception {
        RunSseProperties beating = new RunSseProperties(Duration.ofMillis(10), Duration.ofMillis(10),
                Duration.ofMinutes(1), 100, 2);
        try (RunSseScheduler two = new RunSseScheduler(2)) {
            source.append(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger writers = new AtomicInteger();
            AtomicInteger maxWriters = new AtomicInteger();
            RecordingSink slow = new RecordingSink() {
                @Override
                public void write(String block) throws IOException {
                    maxWriters.accumulateAndGet(writers.incrementAndGet(), Math::max);
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        writers.decrementAndGet();
                    }
                    super.write(block);
                }
            };
            RunEventStream slowStream = new RunEventStream(RUN, 0, source, slow, signals, two, beating, () -> { });
            slowStream.start();
            Thread.sleep(200); // heartbeat·catch-up이 여러 번 돈다

            FakeSource otherSource = new FakeSource();
            otherSource.append(2);
            otherSource.appendTerminal();
            RecordingSink other = new RecordingSink();
            new RunEventStream(UUID.randomUUID(), 0, otherSource, other, signals, two, beating, () -> { }).start();
            await(other::completed);
            assertThat(maxWriters).as("한 연결의 동시 쓰기").hasValue(1);

            release.countDown();
            slowStream.close();
        }
    }

    @Test
    @DisplayName("복원·조회 실패는 정상 종료가 아니라 오류 종료다(즉시 재접속 루프 방지)")
    void runtimeFailureEndsWithError() throws Exception {
        source.append(1);
        RunFrameSource broken = new RunFrameSource() {
            @Override
            public Optional<Progress> progress(UUID runId) {
                return source.progress(runId);
            }

            @Override
            public List<OutboundFrame> after(UUID runId, long afterSeq, int limit) {
                throw new IllegalStateException("run.completed without a completed assistant message");
            }
        };
        RecordingSink sink = new RecordingSink();
        RunEventStream stream = new RunEventStream(RUN, 0, broken, sink, signals, scheduler, FAST,
                closed::incrementAndGet);
        stream.start();
        await(() -> sink.failure() != null);
        assertThat(sink.completed()).isFalse();
        assertThat(stream.isClosed()).isTrue();
        assertThat(signals.listeners()).isZero();
        assertThat(closed).hasValue(1);
    }

    @Test
    @DisplayName("브라우저 단절(쓰기 실패)은 구독·예약 작업만 해제한다")
    void disconnectReleasesSubscription() throws Exception {
        source.append(1);
        RecordingSink gone = new RecordingSink() {
            @Override
            public void write(String block) throws IOException {
                throw new IOException("broken pipe");
            }
        };
        RunEventStream stream = stream(0, gone, FAST);
        stream.start();
        await(stream::isClosed);
        assertThat(signals.listeners()).isZero();
        assertThat(closed).hasValue(1);
        assertThat(gone.completed()).as("단절은 정상 종료가 아니다").isFalse();

        int reads = source.reads.get();
        signals.fire(RUN, 1);
        stream.wake();
        Thread.sleep(100);
        assertThat(source.reads.get()).isEqualTo(reads);
    }

    @Test
    @DisplayName("run이 사라지면(Note 삭제) 연결을 닫는다")
    void runGoneCompletes() throws Exception {
        source.gone = true;
        RecordingSink sink = new RecordingSink();
        stream(0, sink, FAST).start();
        await(sink::completed);
        assertThat(sink.blocks()).isEmpty();
    }

    @Test
    @DisplayName("heartbeat는 id·event·data 없는 comment이고 cursor를 바꾸지 않는다")
    void heartbeatIsCommentOnly() throws Exception {
        RunSseProperties beating = new RunSseProperties(Duration.ofMillis(30), Duration.ofHours(1),
                Duration.ofMinutes(1), 100, 4);
        source.append(1);
        RecordingSink sink = new RecordingSink();
        RunEventStream stream = stream(0, sink, beating);
        stream.start();
        await(() -> sink.blocks().stream().filter(OutboundFrame.HEARTBEAT::equals).count() >= 3);
        assertThat(sink.seqs()).containsExactly(1L);
        assertThat(stream.cursor()).isEqualTo(1);
        assertThat(OutboundFrame.HEARTBEAT).isEqualTo(": heartbeat\n\n");
        stream.close();
    }

    // ------------------------------------------------------------------

    private RunEventStream stream(long afterSeq, RunEventSink sink, RunSseProperties properties) {
        return new RunEventStream(RUN, afterSeq, source, sink, signals, scheduler, properties, closed::incrementAndGet);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met in time");
            }
            Thread.sleep(5);
        }
    }

    /** 저장소 흉내: append-only, seq는 1부터 +1. terminal을 append하면 상태도 terminal(같은 transaction). */
    static class FakeSource implements RunFrameSource {
        final List<OutboundFrame> frames = new CopyOnWriteArrayList<>();
        final AtomicInteger reads = new AtomicInteger();
        volatile boolean terminal;
        volatile boolean gone;
        volatile Runnable onFirstRead;

        synchronized void append(int count) {
            for (int i = 0; i < count; i++) {
                long seq = frames.size() + 1;
                frames.add(new OutboundFrame(seq, false,
                        OutboundFrame.sse("activity.updated", RUN + ":" + seq, "{\"seq\":" + seq + "}")));
            }
        }

        synchronized void appendTerminal() {
            long seq = frames.size() + 1;
            frames.add(new OutboundFrame(seq, true, OutboundFrame.sse("run.completed", RUN + ":" + seq, "{}")));
            terminal = true;
        }

        long lastSeq() {
            return frames.size();
        }

        @Override
        public synchronized Optional<Progress> progress(UUID runId) {
            return gone ? Optional.empty() : Optional.of(new Progress(terminal, frames.size()));
        }

        @Override
        public List<OutboundFrame> after(UUID runId, long afterSeq, int limit) {
            reads.incrementAndGet();
            List<OutboundFrame> found = frames.stream().filter(f -> f.seq() > afterSeq).limit(limit).toList();
            Runnable hook = onFirstRead;
            if (hook != null) {
                onFirstRead = null;
                hook.run();
            }
            return found;
        }
    }

    static class RecordingSink implements RunEventSink {
        private final List<String> blocks = new CopyOnWriteArrayList<>();
        private volatile boolean completed;
        private volatile Throwable failure;

        @Override
        public void write(String block) throws IOException {
            blocks.add(block);
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void fail(Throwable error) {
            failure = error;
        }

        Throwable failure() {
            return failure;
        }

        boolean completed() {
            return completed;
        }

        List<String> blocks() {
            return blocks;
        }

        List<Long> seqs() {
            return blocks.stream().filter(b -> b.startsWith("event: ")).map(b -> {
                String id = b.split("\n")[1].substring("id: ".length());
                return Long.parseLong(id.substring(id.indexOf(':') + 1));
            }).toList();
        }
    }

    /** 신호 흉내: 구독 수를 세고 테스트가 신호를 직접 보낸다. */
    static class FakeSignals extends RunEventSignals {
        private final List<Listener> listeners = new CopyOnWriteArrayList<>();

        @Override
        public Subscription subscribe(UUID runId, Listener listener) {
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }

        void fire(UUID runId, long lastSeq) {
            listeners.forEach(listener -> listener.committed(runId, lastSeq));
        }

        int listeners() {
            return listeners.size();
        }
    }
}
