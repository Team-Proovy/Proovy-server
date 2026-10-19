package com.proovy.support.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * 제품 테이블 row를 SQL로 직접 만드는 테스트 도구. 애플리케이션 서비스 없이 DB 제약만 검사할 때 쓴다.
 */
public final class ProductRows {

    public static final long ALICE = 1001L;
    public static final long BOB = 1002L;
    public static final long ALICE_NOTE = 2001L;
    public static final long BOB_NOTE = 2002L;
    public static final long ALICE_SECOND_NOTE = 2003L;
    public static final long ALICE_PDF = 3001L;
    public static final long BOB_PDF = 3002L;

    public static final String FINGERPRINT = "f".repeat(64);

    private ProductRows() {
    }

    public record Turn(UUID userMessageId, UUID assistantMessageId) {
    }

    public static Turn insertTurn(Connection c, long noteId) throws SQLException {
        Turn turn = new Turn(UUID.randomUUID(), UUID.randomUUID());
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO chat_messages (message_id, note_id, role, status, user_input, plain_text)
                VALUES (?, ?, 'USER', 'COMPLETED', '{"text":"1번 문제 풀어줘"}', '1번 문제 풀어줘'),
                       (?, ?, 'ASSISTANT', 'PENDING', NULL, NULL)
                """)) {
            ps.setObject(1, turn.userMessageId());
            ps.setLong(2, noteId);
            ps.setObject(3, turn.assistantMessageId());
            ps.setLong(4, noteId);
            ps.executeUpdate();
        }
        return turn;
    }

    public static UUID insertPendingRun(Connection c, String requestId, long userId, long noteId, Turn turn)
            throws SQLException {
        UUID runId = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO ai_runs (run_id, request_id, user_id, note_id, user_message_id, assistant_message_id,
                                     request_fingerprint, request_snapshot, policy_snapshot, budget_policy_version,
                                     request_deadline_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, '{"input":{"text":"1번 문제 풀어줘"}}', '{"limits":{}}', 'budget-v1',
                        now() + interval '10 minutes')
                """)) {
            ps.setObject(1, runId);
            ps.setString(2, requestId);
            ps.setLong(3, userId);
            ps.setLong(4, noteId);
            ps.setObject(5, turn.userMessageId());
            ps.setObject(6, turn.assistantMessageId());
            ps.setString(7, FINGERPRINT);
            ps.executeUpdate();
        }
        return runId;
    }

    /** 새 turn + PENDING run. */
    public static UUID insertRunWithTurn(Connection c, String requestId, long userId, long noteId)
            throws SQLException {
        return insertPendingRun(c, requestId, userId, noteId, insertTurn(c, noteId));
    }

    /** PENDING → RUNNING (attempt 1, lease 보유). */
    public static void claim(Connection c, UUID runId) throws SQLException {
        update(c, """
                UPDATE ai_runs SET status = 'RUNNING', attempt = attempt + 1, fence_token = 'fence-' || (attempt + 1),
                       started_at = now(), lease_owner = 'coordinator-1', lease_expires_at = now() + interval '30 seconds',
                       heartbeat_at = now()
                WHERE run_id = ?
                """, runId);
    }

    /** RUNNING → COMPLETED substantive (lease 해제). */
    public static void completeSubstantive(Connection c, UUID runId) throws SQLException {
        update(c, """
                UPDATE ai_runs SET status = 'COMPLETED', response_disposition = 'substantive', completed_at = now(),
                       lease_owner = NULL, lease_expires_at = NULL
                WHERE run_id = ?
                """, runId);
    }

    public static int update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps.executeUpdate();
        }
    }

    public static long count(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
