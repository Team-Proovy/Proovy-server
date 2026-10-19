package com.proovy.domain.run.coordinator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * r183 B1 회귀: worker는 자기 {@code run()} 안(finally)에서 슬롯 permit을 반환한다. 그 직후 thread가 아직 pool로
 * 돌아가지 않은 순간에 다음 claim의 run이 들어와도 거부되면 안 된다(거부되면 claim·fence를 발급한 정상 run이 갈 곳을 잃는다).
 */
class RunCoordinatorWorkerPoolTest {

    @Test
    void runSubmittedRightAfterSlotReleaseWaitsInsteadOfBeingRejected() throws Exception {
        ThreadPoolExecutor pool = RunCoordinator.workerPool(1);
        Semaphore slots = new Semaphore(1);
        CountDownLatch released = new CountDownLatch(1);
        CountDownLatch threadStillBusy = new CountDownLatch(1);
        CountDownLatch secondRan = new CountDownLatch(1);
        try {
            slots.acquire();
            pool.execute(() -> {
                // RunExecution.run()의 finally → finished(): permit을 먼저 반환한다
                slots.release();
                released.countDown();
                // 로그·metrics·afterExecute 등으로 thread가 아직 비지 않았다
                await(threadStillBusy);
            });
            assertThat(released.await(5, TimeUnit.SECONDS)).isTrue();

            // 다른 control thread의 dispatch: permit을 얻고 claim을 마친 뒤 worker를 시작한다
            assertThat(slots.tryAcquire()).isTrue();
            assertThatCode(() -> pool.execute(secondRan::countDown)).doesNotThrowAnyException();
            // permit 수(슬롯)가 대기열 길이를 막는다: 여기서는 하나뿐이다
            assertThat(pool.getQueue()).hasSize(1);
            assertThat(slots.tryAcquire()).isFalse();

            threadStillBusy.countDown();
            assertThat(secondRan.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            threadStillBusy.countDown();
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
