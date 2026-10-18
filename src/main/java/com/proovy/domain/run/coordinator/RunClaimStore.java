package com.proovy.domain.run.coordinator;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * coordinator의 DB 경계. 모든 메서드는 짧은 자기 transaction(또는 단일 문장)이고 Brain 호출 중에 열려 있지 않다.
 * 시각은 DB {@code now()}를 쓴다(여러 Server 인스턴스가 같은 시계로 lease를 비교한다).
 * <ul>
 *   <li>coordinator lease({@code run_coordinator_leases}, V4): 보유자 하나만 claim한다.</li>
 *   <li>claim: {@code PENDING → RUNNING}, attempt 0 → 1, 새 fence·run lease. attempt를 올리는 유일한 경로다.</li>
 *   <li>run lease·heartbeat·RETRYING 표시·종료 시 lease 만료·runtime cleanup 예약.</li>
 *   <li>stale 회수(1.85): lease가 만료된 {@code RUNNING|RETRYING} run의 lease만 같은 attempt·fence로 인수한다.
 *       attempt·fence를 바꾸지 않고 {@code recovery_count}(V5)만 1 올린다.</li>
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
    private static final String TAKE_OVER = """
            UPDATE ai_runs
            SET lease_owner = ?, lease_expires_at = now() + make_interval(secs => ?), heartbeat_at = now(),
                recovery_count = recovery_count + 1
            WHERE run_id = ? AND status IN ('RUNNING', 'RETRYING') AND attempt >= 1
              AND lease_expires_at <= now() AND request_deadline_at > now()
            RETURNING attempt, fence_token, recovery_count
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
     * 전역 슬롯을 차지하는 실행 수(모든 Server 인스턴스): Brain에 위임됐을 수 있는 비terminal run
     * ({@code attempt >= 1 AND status IN (RUNNING, RETRYING, CANCELING)}). lease 상태와 무관하다 — 종료·crash·OBSOLETE로
     * heartbeat가 끊긴 run도 Brain에서 아직 실행 중일 수 있으므로, terminal(deadline sweep·회수·취소 확정)이 될 때까지 센다.
     * Brain 인스턴스 상한을 전역 슬롯의 대체물로 쓰지 않기 위해서다(spec 07, docs/plan/09).
     */
    public int countActiveExecutions() {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM ai_runs
                WHERE status IN ('RUNNING', 'RETRYING', 'CANCELING') AND attempt >= 1
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

    /**
     * 회수 후보: lease가 만료된 {@code RUNNING|RETRYING} run({@code idx_ai_runs_stale_lease}) 중 deadline 전인 것.
     * deadline이 지난 run은 deadline sweep이 timeout으로 끝낸다. CANCELING은 취소 경로(1.84) 소유라 고르지 않는다.
     */
    public List<UUID> findStale(int limit) {
        return findStale(limit, List.of());
    }

    /** {@link #findStale(int)}에서 {@code excluded}(이 인스턴스에서 worker가 도는 run)를 뺀다. */
    public List<UUID> findStale(int limit, Collection<UUID> excluded) {
        String ids = excluded.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
        return jdbc.queryForList("""
                SELECT run_id FROM ai_runs
                WHERE status IN ('RUNNING', 'RETRYING') AND attempt >= 1 AND lease_expires_at <= now()
                  AND request_deadline_at > now() AND run_id <> ALL (CAST(? AS uuid[]))
                ORDER BY lease_expires_at
                LIMIT ?
                """, UUID.class, ids, limit);
    }

    /**
     * coordinator lease를 쥔 채로 stale run의 lease를 인수한다. attempt·fence는 그대로(새 attempt 금지)이고
     * {@code recovery_count}만 1 오른다. 조건부 UPDATE라 동시 인수·heartbeat 재개·terminal·취소 전환과 겹치면 하나만 이긴다.
     * 비어 있으면 인수하지 않았다(이미 다른 worker·경로가 맡음, lease 보유자 아님).
     */
    public Optional<StaleRun> takeOver(UUID runId, String owner, Duration leaseTtl) {
        return tx.execute(status -> {
            if (jdbc.queryForList(HOLD_LEASE, Integer.class, COORDINATOR, owner).isEmpty()) {
                return Optional.<StaleRun>empty();
            }
            List<StaleRun> taken = jdbc.query(TAKE_OVER, (rs, i) -> new StaleRun(
                    new ClaimedRun(runId, rs.getInt("attempt"), rs.getString("fence_token")),
                    rs.getInt("recovery_count")), owner, seconds(leaseTtl), runId);
            return taken.stream().findFirst();
        });
    }

    /**
     * 이 attempt의 Brain source event(Activity·terminal)가 저장돼 있는지. 있으면 Brain이 그 요청을 등록·시작했던 것이다 —
     * 그 뒤 status가 absent면 미시작이 아니라 실행 소실이다(같은 요청을 보내면 새 graph가 시작된다).
     */
    public boolean hasBrainEvents(ClaimedRun run) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM ai_run_events WHERE run_id = ? AND attempt = ? AND source_event_id IS NOT NULL)
                """, Boolean.class, run.runId(), run.attempt());
        return Boolean.TRUE.equals(exists);
    }

    /** 인수한 stale run과 인수 뒤 누적 회수 횟수. */
    public record StaleRun(ClaimedRun claim, int recoveryCount) {
    }

    private static double seconds(Duration duration) {
        return duration.toNanos() / 1_000_000_000.0;
    }
}
