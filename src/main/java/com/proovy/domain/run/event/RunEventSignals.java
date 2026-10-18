package com.proovy.domain.run.event;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * commit된 run event의 프로세스 내 깨우기 신호. 이벤트 저장소가 아니다.
 * <ul>
 *   <li>신호는 {@code (runId, 그 transaction이 저장한 마지막 seq)}뿐이고 payload를 싣지 않는다. 구독자는 항상
 *       {@link RunEventStore#findAfter}로 DB에서 읽는다.</li>
 *   <li>append transaction이 commit된 뒤({@code afterCommit})에만 보낸다. rollback된 event는 신호가 없다.</li>
 *   <li>신호는 유실될 수 있다(구독 전 commit, 다른 인스턴스, listener 예외). 구독자는 구독을 먼저 걸고 그다음
 *       자기 cursor 이후를 DB에서 catch-up하며, 신호를 받을 때마다 다시 cursor 이후를 읽는다. 그래서 신호의 seq는
 *       힌트이고 순서·중복은 cursor가 정한다.</li>
 * </ul>
 * listener는 commit한 thread에서 동기로 불리므로 빨리 반환해야 한다(예: SSE pump 깨우기). 예외는 삼키고 기록만 한다.
 */
@Slf4j
@Component
public class RunEventSignals {

    /** commit 뒤 신호를 받는다. */
    @FunctionalInterface
    public interface Listener {
        void committed(UUID runId, long lastSeq);
    }

    /** {@link #close()}로 구독을 해제한다(브라우저 disconnect 등). 여러 번 불러도 된다. */
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    private final Map<UUID, Set<Listener>> listeners = new ConcurrentHashMap<>();

    /**
     * 추가·제거를 모두 map entry의 원자 연산({@code compute}) 안에서 한다. 그래야 마지막 구독 해제가 entry를 지우는 순간
     * 새 구독이 map에서 떨어진 set에 붙어 신호를 영영 못 받는 경합이 없다.
     */
    public Subscription subscribe(UUID runId, Listener listener) {
        listeners.compute(runId, (key, set) -> {
            Set<Listener> target = set == null ? new CopyOnWriteArraySet<>() : set;
            target.add(listener);
            return target;
        });
        return () -> listeners.computeIfPresent(runId, (key, set) -> {
            set.remove(listener);
            return set.isEmpty() ? null : set;
        });
    }

    /** 현재 transaction이 commit되면 신호를 보낸다. transaction 밖에서 부르면 오류다(저장 경로는 항상 transaction 안이다). */
    void signalAfterCommit(UUID runId, long lastSeq) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("run event signals require an active transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deliver(runId, lastSeq);
            }
        });
    }

    void deliver(UUID runId, long lastSeq) {
        Set<Listener> targets = listeners.get(runId);
        if (targets == null) {
            return;
        }
        for (Listener listener : targets) {
            try {
                listener.committed(runId, lastSeq);
            } catch (RuntimeException e) {
                // 신호 유실은 cursor catch-up으로 복구된다. 예외 message에는 payload가 없지만 원인 객체는 남기지 않는다.
                log.warn("run event listener failed: runId={}, seq={}, error={}", runId, lastSeq,
                        e.getClass().getSimpleName());
            }
        }
    }

    int listenerCount(UUID runId) {
        Set<Listener> targets = listeners.get(runId);
        return targets == null ? 0 : targets.size();
    }
}
