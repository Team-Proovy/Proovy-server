package com.proovy.domain.run.coordinator;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * coordinator의 DB 경계. 모든 메서드는 짧은 자기 transaction(또는 단일 문장)이고 Brain 호출 중에 열려 있지 않다.
 * 시각은 DB {@code now()}를 쓴다(여러 Server 인스턴스가 같은 시계로 lease를 비교한다).
 * <ul>
 *   <li>coordinator lease({@code run_coordinator_leases}, V4): 보유자 하나만 claim한다.</li>
 *   <li>claim: {@code PENDING → RUNNING}, attempt 0 → 1, 새 fence·run lease. attempt를 올리는 유일한 경로다.</li>
 *   <li>run lease·heartbeat·RETRYING 표시·종료 시 lease 만료·runtime cleanup 예약.</li>
 * </ul>
 * 잠금은 {@code ai_runs} 행 하나(claim은 {@code SKIP LOCKED})뿐이라 Note → run 순서의 terminal·취소 transaction과
 * 교착하지 않는다.
 */
public class RunClaimStore {

    static final String COORDINATOR = "run";

    private static final String ACQUIRE_LEASE = """
            INSERT INTO run_coordinator_leases AS l (coordinator, owner, epoch, acquired_at, renewed_at, expires_at)
            VALUES (?, ?, 1, now(), now(), now() + make_interval(secs => ?))
            ON CONFLICT (coordinator) DO UPDATE SET
                owner = EXCLUDED.owner,
                epoch = l.epoch + CASE WHEN l.owner = EXCLUDED.owner THEN 0 ELSE 1 END,
                acquired_at = CASE WHEN l.owner = EXCLUDED.owner THEN l.acquired_at ELSE now() END,
                renewed_at = now(),
                expires_at = EXCLUDED.expires_at
            WHERE l.owner = EXCLUDED.owner OR l.expires_at <= now()
            RETURNING epoch
            """;
    private static final String HOLD_LEASE = """
            SELECT 1 FROM run_coordinator_leases WHERE coordinator = ? AND owner = ? AND expires_at > now() FOR SHARE
            """;
    private static final String CLAIM = """
            WITH picked AS (
                SELECT run_id FROM ai_runs
                WHERE status = 'PENDING' AND request_deadline_at > now() + make_interval(secs => ?)
                ORDER BY created_at, run_id
                LIMIT 1
                FOR UPDATE SKIP LOCKED
            )
            UPDATE ai_runs r
            SET status = 'RUNNING', attempt = 1, fence_token = ?, started_at = now(), lease_owner = ?,
                lease_expires_at = now() + make_interval(secs => ?), heartbeat_at = now()
            FROM picked
            WHERE r.run_id = picked.run_id AND r.status = 'PENDING' AND r.attempt = 0
            RETURNING r.run_id
            """;
    private static final String OWNED = """
            run_id = ? AND attempt = ? AND fence_token = ? AND lease_owner = ?
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public RunClaimStore(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** coordinator lease를 얻거나 갱신한다. 다른 살아 있는 보유자가 있으면 false(이 인스턴스는 claim하지 않는다). */
    public boolean acquireCoordinatorLease(String owner, Duration ttl) {
        List<Long> epoch = jdbc.queryForList(ACQUIRE_LEASE, Long.class, COORDINATOR, owner, seconds(ttl));
        return !epoch.isEmpty();
    }

    /** 종료 시 자기 coordinator lease를 즉시 만료시킨다(다음 인스턴스가 TTL을 기다리지 않게). */
    public void releaseCoordinatorLease(String owner) {
        jdbc.update("""
                UPDATE run_coordinator_leases SET expires_at = renewed_at + interval '1 microsecond'
                WHERE coordinator = ? AND owner = ?
                """, COORDINATOR, owner);
    }

    /**
     * 살아 있는 lease를 가진 실행 수(모든 Server 인스턴스). 이전 프로세스·다른 인스턴스의 실행도 lease가 만료될 때까지
     * 전역 슬롯을 차지한다 — Brain에서 아직 실행 중일 수 있기 때문이다.
     */
    public int countLiveExecutions() {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM ai_runs
                WHERE status IN ('RUNNING', 'RETRYING', 'CANCELING') AND lease_expires_at > now()
                """, Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * coordinator lease를 쥔 채로 가장 오래된 PENDING run 하나를 claim한다. 다른 transaction이 잡은 run(취소 중 등)은
     * 건너뛴다. lease가 없으면(다른 인스턴스로 넘어감) 아무것도 claim하지 않는다.
     */
    public Optional<ClaimedRun> claimNext(String owner, String fenceToken, Duration leaseTtl,
            Duration minRemaining) {
        return tx.execute(status -> {
            if (jdbc.queryForList(HOLD_LEASE, Integer.class, COORDINATOR, owner).isEmpty()) {
                return Optional.<ClaimedRun>empty();
            }
            List<UUID> claimed = jdbc.queryForList(CLAIM, UUID.class, seconds(minRemaining), fenceToken, owner,
                    seconds(leaseTtl));
            return claimed.stream().findFirst().map(runId -> new ClaimedRun(runId, 1, fenceToken));
        });
    }

    /** run lease·heartbeat 갱신. false면 이 attempt·fence·owner의 실행이 아니다(terminal·fence 교체·lease 인수). */
    public boolean heartbeat(ClaimedRun run, String owner, Duration leaseTtl) {
        return jdbc.update("""
                UPDATE ai_runs SET heartbeat_at = now(), lease_expires_at = now() + make_interval(secs => ?)
                WHERE status IN ('RUNNING', 'RETRYING', 'CANCELING') AND
                """ + OWNED, seconds(leaseTtl), run.runId(), run.attempt(), run.fenceToken(), owner) == 1;
    }

    /** 같은 attempt 재전달 전: {@code RUNNING|RETRYING → RETRYING}, retry_count + 1. false면 이 실행이 아니다. */
    public boolean markRetrying(ClaimedRun run, String owner) {
        return jdbc.update("""
                UPDATE ai_runs SET status = 'RETRYING', retry_count = retry_count + 1
                WHERE status IN ('RUNNING', 'RETRYING') AND
                """ + OWNED, run.runId(), run.attempt(), run.fenceToken(), owner) == 1;
    }

    /** 재전달한 stream이 frame을 내기 시작했다: {@code RETRYING → RUNNING}. */
    public boolean markRunning(ClaimedRun run, String owner) {
        return jdbc.update("""
                UPDATE ai_runs SET status = 'RUNNING' WHERE status = 'RETRYING' AND
                """ + OWNED, run.runId(), run.attempt(), run.fenceToken(), owner) == 1;
    }

    /**
     * 종료(worker 정리)할 때 진행 중 run의 lease를 즉시 만료시킨다. run은 terminal이 아니라 그대로 RUNNING|RETRYING이고
     * stale 회수(1.85)의 대상이 된다. transport 종료를 성공으로 보지 않는다.
     */
    public boolean expireLease(ClaimedRun run, String owner) {
        return jdbc.update("""
                UPDATE ai_runs SET lease_expires_at = now()
                WHERE status IN ('RUNNING', 'RETRYING', 'CANCELING') AND
                """ + OWNED, run.runId(), run.attempt(), run.fenceToken(), owner) == 1;
    }

    /**
     * terminal run의 Brain runtime cleanup 예약(1.82 인계: terminal 확정 경로는 하지 않는다). Brain record가 있을 수 있는
     * run(attempt ≥ 1)만, 한 번만 기록한다. 실제 cleanup 호출·retention은 S13이다.
     */
    public boolean reserveRuntimeCleanup(UUID runId, Duration delay) {
        return jdbc.update("""
                UPDATE ai_runs SET runtime_cleanup_at = now() + make_interval(secs => ?)
                WHERE run_id = ? AND status IN ('COMPLETED', 'FAILED', 'CANCELED') AND attempt >= 1
                  AND runtime_cleanup_at IS NULL
                """, seconds(delay), runId) == 1;
    }

    /** 요청 deadline이 지난 진행 중 run({@code idx_ai_runs_deadline}). CANCELING은 취소 경로(1.84)가 끝낸다. */
    public List<UUID> findDeadlinePassed(int limit) {
        return jdbc.queryForList("""
                SELECT run_id FROM ai_runs
                WHERE status IN ('PENDING', 'RUNNING', 'RETRYING') AND request_deadline_at <= now()
                ORDER BY request_deadline_at
                LIMIT ?
                """, UUID.class, limit);
    }

    private static double seconds(Duration duration) {
        return duration.toNanos() / 1_000_000_000.0;
    }
}
