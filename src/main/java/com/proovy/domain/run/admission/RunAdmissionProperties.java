package com.proovy.domain.run.admission;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code proovy.run.admission.*} — 새 run 접수 정책.
 *
 * @param rateLimit       사용자별 반복 요청 제한(RUN4291). 같은 requestId 재전송(replay)은 세지 않는다
 * @param maxTransactions unique 경합·lock 실패 뒤 같은 body로 접수 transaction을 다시 여는 최대 횟수(첫 시도 포함)
 */
@ConfigurationProperties(prefix = "proovy.run.admission")
public record RunAdmissionProperties(
        @DefaultValue RateLimit rateLimit,
        @DefaultValue("3") int maxTransactions) {

    public RunAdmissionProperties {
        if (maxTransactions < 1 || maxTransactions > 10) {
            throw new IllegalArgumentException("proovy.run.admission.max-transactions must be within 1..10");
        }
    }

    /**
     * 최근 {@code window} 안에 접수된 사용자 run이 {@code maxRequests}개 이상이면 새 접수를 거부한다.
     * 기준은 {@code ai_runs(user_id, created_at)}이고 상태(terminal 포함)와 Note를 가리지 않는다.
     */
    public record RateLimit(
            @DefaultValue("20") int maxRequests,
            @DefaultValue("1m") Duration window) {

        public RateLimit {
            if (maxRequests < 1) {
                throw new IllegalArgumentException("proovy.run.admission.rate-limit.max-requests must be positive");
            }
            if (window == null || window.isNegative() || window.isZero() || window.compareTo(Duration.ofHours(1)) > 0) {
                throw new IllegalArgumentException("proovy.run.admission.rate-limit.window must be within (0, 1h]");
            }
        }
    }
}
