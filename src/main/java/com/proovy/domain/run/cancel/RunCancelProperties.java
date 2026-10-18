package com.proovy.domain.run.cancel;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code proovy.run.cancel.*} — RUNNING/RETRYING 취소의 CANCELED 확정(task 1.84). 기본값은 {@code @DefaultValue}다.
 *
 * @param enabled        CANCELING run 주기 확정 작업을 돌린다. 끄면 취소 API 직후 한 번만 확인하고, 그 뒤 확정은 다른 인스턴스의
 *                       주기 작업에 맡긴다(테스트·점검용. 운영은 켠다)
 * @param sweepInterval  CANCELING run을 다시 확인하는 주기. 매번 Brain {@code cancel}(멱등)을 다시 보내 그 ack state로 정리를 확인한다
 * @param cleanupTimeout {@code cancel_requested_at}부터 Brain 정리 확인을 기다리는 상한. 넘으면 확인 없이 CANCELED로 확정한다.
 *                       기본 2분 = Brain의 끊긴 wind-down 정리 상한(writer lease TTL 30s × 4, internal-run-v1 tail)보다 길게 —
 *                       S14 배포 값과 함께 확정
 * @param batchSize      주기 한 번에 확인하는 CANCELING run 수(오래된 취소 요청부터)
 */
@ConfigurationProperties(prefix = "proovy.run.cancel")
public record RunCancelProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("2s") Duration sweepInterval,
        @DefaultValue("2m") Duration cleanupTimeout,
        @DefaultValue("20") int batchSize) {

    public RunCancelProperties {
        requirePositive(sweepInterval, "sweep-interval");
        requirePositive(cleanupTimeout, "cleanup-timeout");
        if (batchSize < 1 || batchSize > 200) {
            throw new IllegalArgumentException("proovy.run.cancel.batch-size must be within 1..200");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("proovy.run.cancel." + name + " must be positive");
        }
    }
}
