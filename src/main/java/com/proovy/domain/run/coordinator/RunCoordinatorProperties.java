package com.proovy.domain.run.coordinator;

import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code proovy.run.coordinator.*} — 브라우저와 독립적인 단일 Server run coordinator(task 1.83).
 * 기동 시 한 번 검사하고 실행 중에 바꾸지 않는다. 값은 run의 deadline·예산을 바꾸지 않는다(그 값은 접수 snapshot이 고정한다).
 *
 * @param enabled               coordinator를 켠다. 켜면 Brain client({@code proovy.brain.base-url})가 있어야 한다
 * @param instanceId            lease owner 값. 비우면 host 이름과 무작위 접미사로 만든다
 * @param maxConcurrentRuns     전역 실행 슬롯(Server가 동시에 Brain에 위임하는 run 수)
 * @param brainCapacity         Brain 배포 상한. 전역 슬롯은 {@code instances × perInstance}를 넘을 수 없다
 * @param pollInterval          PENDING run 조회 주기(빈 슬롯이 있을 때만 claim한다)
 * @param heartbeatInterval     run lease·coordinator lease 갱신 주기
 * @param leaseTtl              heartbeat가 끊긴 뒤 stale로 보는 시간(docs/plan/09 시작값 90초)
 * @param deadlineSweepInterval 요청 deadline이 지난 run을 timeout으로 끝내는 주기
 * @param minDispatchRemaining  남은 deadline이 이보다 짧으면 새로 claim하지 않는다(deadline sweep이 끝낸다)
 * @param redelivery            살아 있는 실행의 같은 attempt 재전달·status 조회 한도
 * @param runtimeCleanupDelay   terminal 확정 뒤 Brain runtime cleanup 예약 시각({@code runtime_cleanup_at}) 지연
 * @param shutdownTimeout       종료 시 worker 정리를 기다리는 최대 시간
 */
@ConfigurationProperties(prefix = "proovy.run.coordinator")
public record RunCoordinatorProperties(
        @DefaultValue("false") boolean enabled,
        String instanceId,
        @DefaultValue("4") int maxConcurrentRuns,
        @DefaultValue BrainCapacity brainCapacity,
        @DefaultValue("1s") Duration pollInterval,
        @DefaultValue("15s") Duration heartbeatInterval,
        @DefaultValue("90s") Duration leaseTtl,
        @DefaultValue("5s") Duration deadlineSweepInterval,
        @DefaultValue("5s") Duration minDispatchRemaining,
        @DefaultValue Redelivery redelivery,
        @DefaultValue("0s") Duration runtimeCleanupDelay,
        @DefaultValue("30s") Duration shutdownTimeout) {

    static final Pattern INSTANCE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,99}$");
    private static final String PREFIX = "proovy.run.coordinator.";

    public RunCoordinatorProperties {
        if (instanceId != null && !instanceId.isBlank() && !INSTANCE_ID.matcher(instanceId).matches()) {
            throw new IllegalArgumentException(PREFIX + "instance-id must match " + INSTANCE_ID.pattern());
        }
        if (maxConcurrentRuns < 1 || maxConcurrentRuns > 64) {
            throw new IllegalArgumentException(PREFIX + "max-concurrent-runs must be within 1..64");
        }
        // Brain 인스턴스 상한(503 capacity)은 마지막 방어선이지 전역 슬롯의 대체물이 아니다(docs/plan/09).
        if (maxConcurrentRuns > brainCapacity.total()) {
            throw new IllegalArgumentException(PREFIX + "max-concurrent-runs (" + maxConcurrentRuns
                    + ") must not exceed brain-capacity.instances x per-instance (" + brainCapacity.total() + ")");
        }
        positive(pollInterval, "poll-interval");
        positive(heartbeatInterval, "heartbeat-interval");
        positive(leaseTtl, "lease-ttl");
        positive(deadlineSweepInterval, "deadline-sweep-interval");
        positive(shutdownTimeout, "shutdown-timeout");
        if (leaseTtl.compareTo(heartbeatInterval.multipliedBy(3)) < 0) {
            throw new IllegalArgumentException(PREFIX + "lease-ttl must be at least 3 x heartbeat-interval");
        }
        notNegative(minDispatchRemaining, "min-dispatch-remaining");
        notNegative(runtimeCleanupDelay, "runtime-cleanup-delay");
    }

    /**
     * @param instances   Brain Cloud Run 최대 인스턴스 수(MVP 3)
     * @param perInstance Brain 인스턴스당 동시 실행 상한 {@code MAX_CONCURRENT_RUNS}(4)
     */
    public record BrainCapacity(
            @DefaultValue("3") int instances,
            @DefaultValue("4") int perInstance) {

        public BrainCapacity {
            if (instances < 1 || perInstance < 1) {
                throw new IllegalArgumentException(PREFIX + "brain-capacity values must be positive");
            }
        }

        public int total() {
            return Math.multiplyExact(instances, perInstance);
        }
    }

    /**
     * 같은 attempt·fence의 재전달 한도. 재전달은 Brain에서 미시작(START)·기존 실행 관찰(OBSERVE)·저장 terminal 재전달만
     * 만들고 새 graph epoch를 만들지 않는다. 모든 재전달·조회는 run의 고정 deadline 안에서만 한다.
     *
     * @param maxResends      run 하나의 stream 재전달 최대 횟수(capacity·연결 실패·status 뒤 재연결 합계)
     * @param maxStatusChecks 연속 status 조회 실패 허용 횟수
     * @param initialBackoff  첫 재전달 전 대기
     * @param maxBackoff      재전달 대기 상한(지수 증가)
     */
    public record Redelivery(
            @DefaultValue("6") int maxResends,
            @DefaultValue("3") int maxStatusChecks,
            @DefaultValue("1s") Duration initialBackoff,
            @DefaultValue("15s") Duration maxBackoff) {

        public Redelivery {
            if (maxResends < 0 || maxResends > 20) {
                throw new IllegalArgumentException(PREFIX + "redelivery.max-resends must be within 0..20");
            }
            if (maxStatusChecks < 1 || maxStatusChecks > 10) {
                throw new IllegalArgumentException(PREFIX + "redelivery.max-status-checks must be within 1..10");
            }
            positive(initialBackoff, "redelivery.initial-backoff");
            positive(maxBackoff, "redelivery.max-backoff");
            if (maxBackoff.compareTo(initialBackoff) < 0) {
                throw new IllegalArgumentException(PREFIX + "redelivery.max-backoff must be >= initial-backoff");
            }
        }

        /** {@code n}번째(1부터) 재시도 전 대기: initial × 2^(n-1), 상한 maxBackoff. */
        public Duration backoff(int n) {
            Duration delay = initialBackoff;
            for (int i = 1; i < n && delay.compareTo(maxBackoff) < 0; i++) {
                delay = delay.multipliedBy(2);
            }
            return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
        }
    }

    private static void positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(PREFIX + name + " must be positive");
        }
    }

    private static void notNegative(Duration value, String name) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException(PREFIX + name + " must not be negative");
        }
    }
}
