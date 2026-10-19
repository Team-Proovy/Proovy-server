package com.proovy.domain.run.stream;

import com.proovy.domain.run.contract.BrowserRunContract;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code proovy.run.sse.*} — 브라우저 run SSE 연결(POST /api/conversations, GET /api/ai-runs/{runId}/events).
 *
 * @param heartbeat       live 연결 heartbeat comment 주기. 계약 값은 15초(browser-run-v1 D10)이고 테스트만 줄인다
 * @param catchUpInterval 신호가 없어도 cursor 이후를 DB에서 다시 확인하는 주기. 신호 유실(다른 인스턴스 commit,
 *                        listener 예외)에서 이 주기 안에 복구된다. 한 번은 {@code ai_runs} 한 행 조회다
 * @param timeout         연결 최대 유지 시간. 지나면 연결만 닫히고 브라우저는 마지막 event ID로 GET 재접속한다
 * @param batchSize       DB catch-up 한 번에 읽는 event 수
 * @param threads         replay·live 전송과 heartbeat를 하는 연결 공용 thread 수. 연결마다 동시에 최대 한 개만 쓴다
 */
@ConfigurationProperties(prefix = "proovy.run.sse")
public record RunSseProperties(
        @DefaultValue("15s") Duration heartbeat,
        @DefaultValue("5s") Duration catchUpInterval,
        @DefaultValue("30m") Duration timeout,
        @DefaultValue("100") int batchSize,
        @DefaultValue("8") int threads) {

    public RunSseProperties {
        requirePositive(heartbeat, "heartbeat");
        requirePositive(catchUpInterval, "catch-up-interval");
        requirePositive(timeout, "timeout");
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("proovy.run.sse.batch-size must be within 1..1000");
        }
        if (threads < 1 || threads > 64) {
            throw new IllegalArgumentException("proovy.run.sse.threads must be within 1..64");
        }
    }

    /** 계약 기본값(heartbeat 15초)을 쓰는 설정. */
    public static RunSseProperties defaults() {
        return new RunSseProperties(Duration.ofSeconds(BrowserRunContract.HEARTBEAT_SECONDS), Duration.ofSeconds(5),
                Duration.ofMinutes(30), 100, 8);
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("proovy.run.sse." + name + " must be positive");
        }
    }
}
