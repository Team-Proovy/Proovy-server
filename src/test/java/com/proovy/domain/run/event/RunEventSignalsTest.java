package com.proovy.domain.run.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RunEventSignalsTest {

    @Test
    @DisplayName("마지막 구독 해제와 새 구독이 경합해도 새 구독은 map에 남아 신호를 받는다")
    void subscribeRacingLastCloseStaysAttached() throws Exception {
        RunEventSignals signals = new RunEventSignals();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 2_000; round++) {
                UUID run = UUID.randomUUID();
                RunEventSignals.Subscription first = signals.subscribe(run, (id, seq) -> {
                });
                AtomicLong received = new AtomicLong();
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<?> closing = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    first.close();
                    return null;
                });
                Future<RunEventSignals.Subscription> joining = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return signals.subscribe(run, (id, seq) -> received.set(seq));
                });
                closing.get(5, TimeUnit.SECONDS);
                RunEventSignals.Subscription second = joining.get(5, TimeUnit.SECONDS);

                assertThat(signals.listenerCount(run)).as("round %d", round).isEqualTo(1);
                signals.deliver(run, round + 1);
                assertThat(received.get()).as("round %d", round).isEqualTo(round + 1);
                second.close();
                second.close();
                assertThat(signals.listenerCount(run)).isZero();
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
