package com.proovy.db;

import com.proovy.support.db.PostgresTestDatabase;
import com.proovy.support.db.ProductRows.Turn;
import org.junit.jupiter.api.*;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static com.proovy.support.db.ProductRows.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 실제 Postgres에서 S07 저장 구조의 DB 제약을 검사한다 (task 1.74).
 *
 * <p>각 테스트는 autocommit을 끈 연결에서 실행하고 끝에 rollback한다. 위반 기대 구문은
 * savepoint로 감싸 같은 transaction에서 이어 검사한다. 동시성·rollback 테스트만 별도 연결로 commit한다.
 */
@Tag("postgres")
class ProductConstraintsPostgresTest {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FK_VIOLATION = "23503";
    private static final String CHECK_VIOLATION = "23514";
    private static final String TRIGGER_VIOLATION = "23000";

    private static PostgresTestDatabase db;
    private Connection c;

    @BeforeAll
    static void createDatabase() {
        db = PostgresTestDatabase.createMigrated();
        db.runScript("db/seed/product-seed.sql");
    }

    @AfterAll
    static void dropDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void open() throws SQLException {
        c = db.connect();
        c.setAutoCommit(false);
    }

    @AfterEach
    void rollback() throws SQLException {
        c.rollback();
        c.close();
    }

    // ------------------------------------------------------------------
    // baseline 형태
    // ------------------------------------------------------------------

    @Test
    void baselineHasNoChatSessionOrThreadId() throws SQLException {
        assertThat(count(c, "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('chat_sessions','message_assets','message_attachments','message_tools','note_embeddings')"))
                .isZero();
        assertThat(count(c, "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND column_name IN ('chat_session_id','thread_id','external_thread_id')"))
                .isZero();
        // 메시지는 Note에 직접 연결된다
        assertThat(count(c, """
                SELECT count(*) FROM information_schema.table_constraints tc
                JOIN information_schema.constraint_column_usage ccu ON tc.constraint_name = ccu.constraint_name
                WHERE tc.table_name = 'chat_messages' AND tc.constraint_type = 'FOREIGN KEY' AND ccu.table_name = 'notes'
                """)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // request unique · Active Run partial unique
    // ------------------------------------------------------------------

    @Test
    void requestIdIsUnique() throws SQLException {
        insertRunWithTurn(c, "req-unique-1", ALICE, ALICE_NOTE);
        expectViolation(UNIQUE_VIOLATION, "uq_ai_runs_request",
                () -> insertRunWithTurn(c, "req-unique-1", ALICE, ALICE_SECOND_NOTE));
    }

    @Test
    void oneActiveRunPerNoteIncludingCanceling() throws SQLException {
        UUID first = insertRunWithTurn(c, "req-active-1", ALICE, ALICE_NOTE);
        expectViolation(UNIQUE_VIOLATION, "ux_ai_runs_note_active",
                () -> insertRunWithTurn(c, "req-active-2", ALICE, ALICE_NOTE));

        // 다른 Note는 독립적이다
        insertRunWithTurn(c, "req-active-other-note", ALICE, ALICE_SECOND_NOTE);

        // CANCELING은 cleanup 확인 전까지 자리를 유지한다
        claim(c, first);
        update(c, """
                UPDATE ai_runs SET status = 'CANCELING', cancel_cause = 'user_requested',
                       cancel_requested_from = 'RUNNING', cancel_requested_at = now()
                WHERE run_id = ?
                """, first);
        expectViolation(UNIQUE_VIOLATION, "ux_ai_runs_note_active",
                () -> insertRunWithTurn(c, "req-active-3", ALICE, ALICE_NOTE));

        update(c, """
                UPDATE ai_runs SET status = 'CANCELED', completed_at = now(), lease_owner = NULL, lease_expires_at = NULL
                WHERE run_id = ?
                """, first);
        insertRunWithTurn(c, "req-active-4", ALICE, ALICE_NOTE);
    }

    @Test
    void concurrentAdmissionOnSameNoteLeavesOneRun() throws Exception {
        long noteId;
        try (Connection setup = db.connect()) {
            noteId = insertNote(setup, ALICE, "race note");
        }
        int contenders = 6;
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        CyclicBarrier barrier = new CyclicBarrier(contenders);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            String requestId = "req-race-" + i;
            results.add(pool.submit(() -> {
                try (Connection tx = db.connect()) {
                    tx.setAutoCommit(false);
                    Turn turn = insertTurn(tx, noteId);
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        insertPendingRun(tx, requestId, ALICE, noteId, turn);
                        tx.commit();
                        return "committed";
                    } catch (SQLException e) {
                        tx.rollback();
                        return e.getSQLState();
                    }
                }
            }));
        }
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : results) {
            outcomes.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(outcomes).filteredOn("committed"::equals).hasSize(1);
        assertThat(outcomes).filteredOn(UNIQUE_VIOLATION::equals).hasSize(contenders - 1);
        try (Connection check = db.connect()) {
            assertThat(count(check, "SELECT count(*) FROM ai_runs WHERE note_id = ?", noteId)).isEqualTo(1);
            // 거부된 요청은 메시지를 남기지 않는다 (같은 transaction rollback)
            assertThat(count(check, "SELECT count(*) FROM chat_messages WHERE note_id = ?", noteId)).isEqualTo(2);
            update(check, "DELETE FROM notes WHERE note_id = ?", noteId);
        }
    }

    // ------------------------------------------------------------------
    // 접수 transaction rollback
    // ------------------------------------------------------------------

    @Test
    void failedAdmissionRollsBackNoteMessagesRunAndCharge() throws SQLException {
        try (Connection tx = db.connect()) {
            tx.setAutoCommit(false);
            long noteId = insertNote(tx, ALICE, "auto note");
            Turn turn = insertTurn(tx, noteId);
            UUID runId = insertPendingRun(tx, "req-rollback-1", ALICE, noteId, turn);
            insertCharge(tx, "req-rollback-1", ALICE, runId, 1, 0, 0);
            try {
                // 같은 transaction의 후속 저장(잘못된 차감 분배)이 실패하면 접수 전체가 rollback된다
                insertChargeWithAmount(tx, "req-rollback-2", ALICE, 5, 1, 0, 0);
                fail("expected a check violation");
            } catch (SQLException expected) {
                assertThat(expected.getSQLState()).isEqualTo(CHECK_VIOLATION);
                tx.rollback();
            }
        }
        try (Connection check = db.connect()) {
            assertThat(count(check, "SELECT count(*) FROM notes WHERE title = 'auto note'")).isZero();
            assertThat(count(check, "SELECT count(*) FROM ai_runs WHERE request_id LIKE 'req-rollback-%'")).isZero();
            assertThat(count(check, "SELECT count(*) FROM request_usage_charges WHERE request_id LIKE 'req-rollback-%'")).isZero();
        }
    }

    // ------------------------------------------------------------------
    // 소유/FK 경계
    // ------------------------------------------------------------------

    @Test
    void runMustBelongToNoteOwnerAndMessagesOfSameNote() throws SQLException {
        Turn bobTurn = insertTurn(c, BOB_NOTE);
        // alice가 bob의 Note에 run을 만들 수 없다
        expectViolation(FK_VIOLATION, "fk_ai_runs_note_owner",
                () -> insertPendingRun(c, "req-owner-1", ALICE, BOB_NOTE, bobTurn));
        // 다른 Note의 메시지를 run에 연결할 수 없다
        expectViolation(FK_VIOLATION, "fk_ai_runs_user_message",
                () -> insertPendingRun(c, "req-owner-2", ALICE, ALICE_NOTE, bobTurn));
        // user/assistant 역할을 뒤바꿀 수 없다
        Turn aliceTurn = insertTurn(c, ALICE_NOTE);
        expectViolation(FK_VIOLATION, "fk_ai_runs_user_message", () -> insertPendingRun(c, "req-owner-3", ALICE, ALICE_NOTE,
                new Turn(aliceTurn.assistantMessageId(), aliceTurn.userMessageId())));
        // asset 소유자는 Note 소유자와 같아야 한다
        expectViolation(FK_VIOLATION, "fk_assets_note_owner", () -> update(c, """
                INSERT INTO assets (user_id, note_id, file_name, file_size, mime_type, object_key, source, status, created_at, updated_at)
                VALUES (?, ?, 'x.pdf', 1, 'application/pdf', 'k', 'upload', 'PENDING', now(), now())
                """, ALICE, BOB_NOTE));
    }

    @Test
    void assetDependenciesStayInsideTheRunNoteAndAreFixedAtCreation() throws SQLException {
        UUID runId = insertRunWithTurn(c, "req-dep-1", ALICE, ALICE_NOTE);
        insertDependency(c, runId, ALICE_NOTE, ALICE_PDF, "VIEWER_FOCUS");
        insertDependency(c, runId, ALICE_NOTE, ALICE_PDF, "CORPUS_SNAPSHOT");

        // 다른 Note의 asset 의존은 거부된다 (deferred FK: commit 또는 IMMEDIATE 전환 시점에 검사)
        expectViolation(FK_VIOLATION, "fk_run_asset_dependencies_asset", () -> {
            insertDependency(c, runId, ALICE_NOTE, BOB_PDF, "CORPUS_SNAPSHOT");
            update(c, "SET CONSTRAINTS fk_run_asset_dependencies_asset IMMEDIATE");
        });
        expectViolation(TRIGGER_VIOLATION, "immutable",
                () -> update(c, "UPDATE run_asset_dependencies SET content_version = 2 WHERE run_id = ?", runId));

        claim(c, runId);
        expectViolation(TRIGGER_VIOLATION, "fixed at run creation",
                () -> insertDependency(c, runId, ALICE_NOTE, 3003L, "CORPUS_SNAPSHOT"));
        // 의존 중인 asset은 hard delete할 수 없다 (DELETING 경로 강제). deferred FK라 commit 시점에 검사된다.
        expectViolation(FK_VIOLATION, "fk_run_asset_dependencies_asset", () -> {
            update(c, "DELETE FROM assets WHERE id = ?", ALICE_PDF);
            update(c, "SET CONSTRAINTS fk_run_asset_dependencies_asset IMMEDIATE");
        });
    }

    @Test
    void assetDeletionStateRequiresOperationAndCleanup() throws SQLException {
        expectViolation(CHECK_VIOLATION, "ck_assets_deletion",
                () -> update(c, "UPDATE assets SET status = 'DELETING' WHERE id = ?", ALICE_PDF));
        update(c, """
                UPDATE assets SET status = 'DELETING', deletion_operation_id = gen_random_uuid(), deletion_requested_at = now()
                WHERE id = ?
                """, ALICE_PDF);
        expectViolation(CHECK_VIOLATION, "ck_assets_deleted_cleanup",
                () -> update(c, "UPDATE assets SET status = 'DELETED' WHERE id = ?", ALICE_PDF));
        update(c, """
                UPDATE assets SET status = 'DELETED', brain_cleanup_acked_at = now(), storage_cleaned_at = now()
                WHERE id = ?
                """, ALICE_PDF);
    }

    // ------------------------------------------------------------------
    // Note 삭제 cascade · source snapshot 분리
    // ------------------------------------------------------------------

    @Test
    void noteDeletionCascadesRunDataOnlyForThatNoteAndKeepsLedger() throws SQLException {
        UUID aliceRun = insertRunWithTurn(c, "req-cascade-1", ALICE, ALICE_NOTE);
        insertDependency(c, aliceRun, ALICE_NOTE, ALICE_PDF, "VIEWER_FOCUS");
        insertServerEvent(c, aliceRun, ALICE_NOTE, 1, "run.created");
        insertCharge(c, "req-cascade-1", ALICE, aliceRun, 1, 0, 0);
        UUID bobRun = insertRunWithTurn(c, "req-cascade-2", BOB, BOB_NOTE);
        insertServerEvent(c, bobRun, BOB_NOTE, 1, "run.created");

        update(c, "DELETE FROM notes WHERE note_id = ?", ALICE_NOTE);

        assertThat(count(c, "SELECT count(*) FROM ai_runs WHERE note_id = ?", ALICE_NOTE)).isZero();
        assertThat(count(c, "SELECT count(*) FROM ai_run_events WHERE note_id = ?", ALICE_NOTE)).isZero();
        assertThat(count(c, "SELECT count(*) FROM chat_messages WHERE note_id = ?", ALICE_NOTE)).isZero();
        assertThat(count(c, "SELECT count(*) FROM assets WHERE note_id = ?", ALICE_NOTE)).isZero();
        assertThat(count(c, "SELECT count(*) FROM run_asset_dependencies WHERE run_id = ?", aliceRun)).isZero();
        // 다른 Note는 영향이 없다
        assertThat(count(c, "SELECT count(*) FROM ai_runs WHERE run_id = ?", bobRun)).isEqualTo(1);
        assertThat(count(c, "SELECT count(*) FROM ai_run_events WHERE run_id = ?", bobRun)).isEqualTo(1);
        // 차감 이력·request 멱등 키는 남고 run 참조만 끊긴다
        assertThat(count(c, "SELECT count(*) FROM request_usage_charges WHERE request_id = 'req-cascade-1' AND run_id IS NULL"))
                .isEqualTo(1);
    }

    @Test
    void sourceReferenceSnapshotSurvivesAssetDeletion() throws SQLException {
        Turn turn = insertTurn(c, ALICE_NOTE);
        UUID runId = insertPendingRun(c, "req-source-1", ALICE, ALICE_NOTE, turn);
        claim(c, runId);
        String sources = "[{\"source_ref_id\":\"sref-1\",\"asset_id\":\"3003\",\"page_start\":1,\"page_end\":1,\"display_label_snapshot\":\"notes.png\"}]";
        completeAssistant(c, turn.assistantMessageId(), sources);
        completeSubstantive(c, runId);

        update(c, "DELETE FROM assets WHERE id = 3003");

        try (PreparedStatement ps = c.prepareStatement(
                "SELECT source_references::text, content_sha256 FROM chat_messages WHERE message_id = ?")) {
            ps.setObject(1, turn.assistantMessageId());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).contains("\"asset_id\": \"3003\"").contains("notes.png");
                assertThat(rs.getString(2)).isEqualTo("e".repeat(64));
            }
        }
    }

    // ------------------------------------------------------------------
    // 메시지 구조·불변성
    // ------------------------------------------------------------------

    @Test
    void assistantPlaceholderHasNoBodyAndCompletedMessageIsImmutable() throws SQLException {
        Turn turn = insertTurn(c, ALICE_NOTE);
        // STREAMING placeholder에 Candidate 본문을 저장할 수 없다
        expectViolation(CHECK_VIOLATION, "ck_chat_messages_assistant_placeholder", () -> update(c,
                "UPDATE chat_messages SET status = 'STREAMING', plain_text = 'candidate' WHERE message_id = ?",
                turn.assistantMessageId()));
        // 본문 없는 COMPLETED는 거부된다
        expectViolation(CHECK_VIOLATION, "ck_chat_messages_assistant_completed", () -> update(c,
                "UPDATE chat_messages SET status = 'COMPLETED', completed_at = now() WHERE message_id = ?",
                turn.assistantMessageId()));

        // response_pages·source_references 중 하나라도 NULL이면 COMPLETED가 될 수 없다
        expectViolation(CHECK_VIOLATION, "ck_chat_messages_assistant_completed", () -> update(c, """
                UPDATE chat_messages SET status = 'COMPLETED', completed_at = now(), source_references = '[]',
                       plain_text = 'x', content_sha256 = ? WHERE message_id = ?""", "e".repeat(64), turn.assistantMessageId()));
        expectViolation(CHECK_VIOLATION, "ck_chat_messages_assistant_completed", () -> update(c, """
                UPDATE chat_messages SET status = 'COMPLETED', completed_at = now(),
                       response_pages = '[{"response_page_id":"page-1","presentation":"main","result_blocks":[]}]',
                       plain_text = 'x', content_sha256 = ? WHERE message_id = ?""", "e".repeat(64), turn.assistantMessageId()));
        // USER 메시지는 입력 원문 없이 저장할 수 없다
        expectViolation(CHECK_VIOLATION, "ck_chat_messages_user", () -> update(c, """
                INSERT INTO chat_messages (message_id, note_id, role, status, plain_text) VALUES (?, ?, 'USER', 'COMPLETED', 'x')
                """, UUID.randomUUID(), ALICE_NOTE));
        expectViolation(CHECK_VIOLATION, "ck_chat_messages_user", () -> update(c, """
                INSERT INTO chat_messages (message_id, note_id, role, status, user_input, plain_text)
                VALUES (?, ?, 'USER', 'COMPLETED', '{"latex":"x"}', 'x')
                """, UUID.randomUUID(), ALICE_NOTE));

        completeAssistant(c, turn.assistantMessageId(), "[]");
        expectViolation(TRIGGER_VIOLATION, "immutable", () -> update(c,
                "UPDATE chat_messages SET plain_text = 'edited' WHERE message_id = ?", turn.assistantMessageId()));
        expectViolation(TRIGGER_VIOLATION, "immutable", () -> update(c,
                "UPDATE chat_messages SET user_input = '{\"text\":\"edited\"}' WHERE message_id = ?", turn.userMessageId()));
    }

    // ------------------------------------------------------------------
    // run 불변 snapshot · 상태 전이 · fence
    // ------------------------------------------------------------------

    @Test
    void runSnapshotsAreImmutableAndTransitionsAreFenced() throws SQLException {
        UUID runId = insertRunWithTurn(c, "req-run-1", ALICE, ALICE_NOTE);

        expectViolation(TRIGGER_VIOLATION, "immutable", () -> update(c,
                "UPDATE ai_runs SET request_snapshot = '{\"input\":{\"text\":\"changed\"}}' WHERE run_id = ?", runId));
        expectViolation(TRIGGER_VIOLATION, "immutable", () -> update(c,
                "UPDATE ai_runs SET request_deadline_at = request_deadline_at + interval '1 hour' WHERE run_id = ?", runId));
        // PENDING에서 바로 COMPLETED는 허용하지 않는다
        expectViolation(TRIGGER_VIOLATION, "invalid run status transition", () -> update(c,
                "UPDATE ai_runs SET status = 'COMPLETED', response_disposition = 'substantive', completed_at = now() WHERE run_id = ?", runId));

        claim(c, runId);
        update(c, "UPDATE ai_runs SET used_llm_calls = 3, last_event_seq = 4 WHERE run_id = ?", runId);
        expectViolation(TRIGGER_VIOLATION, "monotonic",
                () -> update(c, "UPDATE ai_runs SET used_llm_calls = 1 WHERE run_id = ?", runId));
        expectViolation(TRIGGER_VIOLATION, "monotonic",
                () -> update(c, "UPDATE ai_runs SET attempt = 0, fence_token = NULL, started_at = NULL WHERE run_id = ?", runId));
        // 같은 attempt에서 fence를 바꿀 수 없다
        expectViolation(TRIGGER_VIOLATION, "new attempt",
                () -> update(c, "UPDATE ai_runs SET fence_token = 'forged' WHERE run_id = ?", runId));

        // 이전 fence 조건부 update는 0 row
        claim(c, runId);
        assertThat(update(c, """
                UPDATE ai_runs SET status = 'COMPLETED', response_disposition = 'substantive', completed_at = now(),
                       lease_owner = NULL, lease_expires_at = NULL
                WHERE run_id = ? AND attempt = 1 AND fence_token = 'fence-1'
                """, runId)).isZero();

        // terminal은 lease·grant를 남길 수 없다
        expectViolation(CHECK_VIOLATION, "ck_ai_runs_terminal_released", () -> update(c,
                "UPDATE ai_runs SET status = 'COMPLETED', response_disposition = 'substantive', completed_at = now() WHERE run_id = ?", runId));
        completeSubstantive(c, runId);

        // terminal 뒤에는 runtime cleanup 예약만 바꿀 수 있다
        update(c, "UPDATE ai_runs SET runtime_cleanup_at = now() + interval '1 day' WHERE run_id = ?", runId);
        expectViolation(TRIGGER_VIOLATION, "terminal run", () -> update(c,
                "UPDATE ai_runs SET used_llm_calls = 99 WHERE run_id = ?", runId));
    }

    @Test
    void terminalClassificationConstraints() throws SQLException {
        UUID runId = insertRunWithTurn(c, "req-class-1", ALICE, ALICE_NOTE);
        claim(c, runId);
        String release = ", completed_at = now(), lease_owner = NULL, lease_expires_at = NULL ";
        expectViolation(CHECK_VIOLATION, "ck_ai_runs_clarification_reason_pair", () -> update(c,
                "UPDATE ai_runs SET status = 'COMPLETED', response_disposition = 'clarification_only'" + release + "WHERE run_id = ?", runId));
        expectViolation(CHECK_VIOLATION, "ck_ai_runs_clarification_reason_pair", () -> update(c,
                "UPDATE ai_runs SET status = 'COMPLETED', response_disposition = 'unsupported_only', clarification_reason = 'missing_input'" + release + "WHERE run_id = ?", runId));
        expectViolation(CHECK_VIOLATION, "ck_ai_runs_failure_code_class", () -> update(c,
                "UPDATE ai_runs SET status = 'FAILED', failure_code = 'timeout', failure_class = 'verification'" + release + "WHERE run_id = ?", runId));
        // disposition·class가 NULL인 진행 중 run에도 분류 짝 규칙이 적용된다
        expectViolation(CHECK_VIOLATION, "ck_ai_runs_clarification_reason_pair", () -> update(c,
                "UPDATE ai_runs SET clarification_reason = 'missing_input' WHERE run_id = ?", runId));
        // class 없는 code는 code-class 대응 검사(COALESCE)와 짝 검사 중 먼저 평가되는 쪽이 거부한다
        expectViolation(CHECK_VIOLATION, "ck_ai_runs_failure_", () -> update(c,
                "UPDATE ai_runs SET failure_code = 'timeout' WHERE run_id = ?", runId));
        expectViolation(CHECK_VIOLATION, "ck_ai_runs_failure_pair", () -> update(c,
                "UPDATE ai_runs SET failure_class = 'budget' WHERE run_id = ?", runId));
        // PENDING 취소가 아닌 CANCELING은 실행 상태에서만 시작한다
        UUID pending = insertRunWithTurn(c, "req-class-2", ALICE, ALICE_SECOND_NOTE);
        expectViolation(TRIGGER_VIOLATION, "invalid run status transition", () -> update(c, """
                UPDATE ai_runs SET status = 'CANCELING', cancel_cause = 'user_requested', cancel_requested_from = 'PENDING',
                       cancel_requested_at = now() WHERE run_id = ?""", pending));
        update(c, """
                UPDATE ai_runs SET status = 'CANCELED', cancel_cause = 'user_requested', cancel_requested_from = 'PENDING',
                       cancel_requested_at = now(), completed_at = now() WHERE run_id = ?""", pending);
        update(c, "UPDATE ai_runs SET status = 'COMPLETED', response_disposition = 'clarification_only', clarification_reason = 'corpus_not_ready'"
                + release + "WHERE run_id = ?", runId);
    }

    // ------------------------------------------------------------------
    // event seq · dedup · append-only
    // ------------------------------------------------------------------

    @Test
    void eventSeqAndSourceEventAreUniquePerRun() throws SQLException {
        UUID runId = insertRunWithTurn(c, "req-event-1", ALICE, ALICE_NOTE);
        insertServerEvent(c, runId, ALICE_NOTE, 1, "run.created");
        expectViolation(UNIQUE_VIOLATION, "uq_ai_run_events_seq",
                () -> insertServerEvent(c, runId, ALICE_NOTE, 1, "run.canceling"));

        // Brain Activity는 run이 시작한 attempt 안에서만 온다
        expectViolation(TRIGGER_VIOLATION, "exceeds run", () -> insertActivity(c, runId, 2, "src-evt-1"));
        claim(c, runId);
        assertThat(insertActivity(c, runId, 2, "src-evt-1")).isEqualTo(1);
        // 같은 source event 재수신은 row도 seq도 늘리지 않는다
        assertThat(insertActivity(c, runId, 3, "src-evt-1")).isZero();
        assertThat(count(c, "SELECT count(*) FROM ai_run_events WHERE run_id = ?", runId)).isEqualTo(2);
        assertThat(count(c, "SELECT max(seq) FROM ai_run_events WHERE run_id = ?", runId)).isEqualTo(2);

        // 다른 run은 같은 seq·source id를 쓸 수 있다
        UUID other = insertRunWithTurn(c, "req-event-2", ALICE, ALICE_SECOND_NOTE);
        insertServerEvent(c, other, ALICE_SECOND_NOTE, 1, "run.created");

        expectViolation(TRIGGER_VIOLATION, "immutable",
                () -> update(c, "UPDATE ai_run_events SET payload = '{}' WHERE run_id = ?", runId));
        // append-only: 직접 DELETE는 거부한다 (run/Note 삭제 cascade만 허용)
        expectViolation(TRIGGER_VIOLATION, "direct DELETE",
                () -> update(c, "DELETE FROM ai_run_events WHERE run_id = ?", runId));
        // last_event_seq로 배정하지 않은 seq는 쓸 수 없다
        expectViolation(TRIGGER_VIOLATION, "not allocated", () -> update(c,
                "INSERT INTO ai_run_events (run_id, note_id, seq, event_type) VALUES (?, ?, 99, 'run.canceling')",
                runId, ALICE_NOTE));
        allocateSeq(c, runId, 10);
        // Activity는 dedup 키·attempt가 필요하고 server 소유 event는 source id를 가질 수 없다
        expectViolation(CHECK_VIOLATION, "ck_ai_run_events_activity", () -> update(c, """
                INSERT INTO ai_run_events (run_id, note_id, seq, event_type, activity_id) VALUES (?, ?, 9, 'activity.updated', 'activity-1')
                """, runId, ALICE_NOTE));
        expectViolation(CHECK_VIOLATION, "ck_ai_run_events_server_owned", () -> update(c, """
                INSERT INTO ai_run_events (run_id, note_id, seq, event_type, source_event_id, attempt) VALUES (?, ?, 9, 'run.created', 'src-x', 1)
                """, runId, ALICE_NOTE));
        // event는 run과 같은 Note에 속한다
        expectViolation(FK_VIOLATION, "fk_ai_run_events_run",
                () -> insertServerEvent(c, runId, BOB_NOTE, 10, "run.canceling"));
    }

    // ------------------------------------------------------------------
    // usage ledger: 차감 1회 · 보상 최대 1회
    // ------------------------------------------------------------------

    @Test
    void chargeOncePerRequestAndCompensateAtMostOnce() throws SQLException {
        UUID runId = insertRunWithTurn(c, "req-usage-1", ALICE, ALICE_NOTE);
        insertCharge(c, "req-usage-1", ALICE, runId, 1, 0, 0);
        expectViolation(UNIQUE_VIOLATION, "request_usage_charges_pkey",
                () -> insertCharge(c, "req-usage-1", ALICE, null, 1, 0, 0));
        // 차감은 같은 request_id·user의 run만 가리킨다
        UUID otherRun = insertRunWithTurn(c, "req-usage-2", ALICE, ALICE_SECOND_NOTE);
        expectViolation(FK_VIOLATION, "fk_request_usage_charges_run",
                () -> insertCharge(c, "req-usage-other", ALICE, otherRun, 1, 0, 0));
        expectViolation(FK_VIOLATION, "fk_request_usage_charges_run",
                () -> insertCharge(c, "req-usage-2", BOB, otherRun, 1, 0, 0));

        // 차감 없는 보상·차감량과 다른 보상은 거부된다
        expectViolation(FK_VIOLATION, "fk_request_usage_compensations_charge",
                () -> insertCompensation(c, "req-usage-unknown", "INFRASTRUCTURE_FAILURE", 1));
        expectViolation(FK_VIOLATION, "fk_request_usage_compensations_charge",
                () -> insertCompensation(c, "req-usage-1", "INFRASTRUCTURE_FAILURE", 2));

        insertCompensation(c, "req-usage-1", "PENDING_CANCELED", 1);
        // 취소·clarification·인프라 실패 보상이 경합해도 하나만 남는다
        expectViolation(UNIQUE_VIOLATION, "request_usage_compensations_pkey",
                () -> insertCompensation(c, "req-usage-1", "CLARIFICATION_ONLY", 1));
        assertThat(update(c, """
                INSERT INTO request_usage_compensations (request_id, credit_amount, reason, restored_daily)
                VALUES ('req-usage-1', 1, 'INFRASTRUCTURE_FAILURE', 1) ON CONFLICT (request_id) DO NOTHING
                """)).isZero();

        expectViolation(TRIGGER_VIOLATION, "immutable",
                () -> update(c, "UPDATE request_usage_charges SET charged_daily = 0, charged_free = 1 WHERE request_id = 'req-usage-1'"));
        expectViolation(TRIGGER_VIOLATION, "immutable",
                () -> update(c, "UPDATE request_usage_compensations SET reason = 'BUDGET_EXCEEDED' WHERE request_id = 'req-usage-1'"));
        // ledger row를 지워 다시 보상·차감할 수 없다
        expectViolation(TRIGGER_VIOLATION, "direct DELETE",
                () -> update(c, "DELETE FROM request_usage_compensations WHERE request_id = 'req-usage-1'"));
        expectViolation(TRIGGER_VIOLATION, "direct DELETE",
                () -> update(c, "DELETE FROM request_usage_charges WHERE request_id = 'req-usage-1'"));
    }

    @Test
    void userDeletionCascadesLedgerAndRunEvents() throws SQLException {
        UUID runId = insertRunWithTurn(c, "req-user-delete", BOB, BOB_NOTE);
        insertServerEvent(c, runId, BOB_NOTE, 1, "run.created");
        insertCharge(c, "req-user-delete", BOB, runId, 1, 0, 0);
        insertCompensation(c, "req-user-delete", "PENDING_CANCELED", 1);

        update(c, "DELETE FROM users WHERE user_id = ?", BOB);

        assertThat(count(c, "SELECT count(*) FROM ai_run_events WHERE run_id = ?", runId)).isZero();
        assertThat(count(c, "SELECT count(*) FROM request_usage_charges WHERE user_id = ?", BOB)).isZero();
        assertThat(count(c, "SELECT count(*) FROM request_usage_compensations WHERE request_id = 'req-user-delete'")).isZero();
    }

    @Test
    void concurrentCompensationAppliesOnce() throws Exception {
        String requestId = "req-usage-race";
        try (Connection setup = db.connect()) {
            insertCharge(setup, requestId, BOB, null, 1, 0, 0);
        }
        String[] reasons = {"PENDING_CANCELED", "CLARIFICATION_ONLY", "INFRASTRUCTURE_FAILURE", "VERIFICATION_FAILED"};
        ExecutorService pool = Executors.newFixedThreadPool(reasons.length);
        CyclicBarrier barrier = new CyclicBarrier(reasons.length);
        List<Future<Integer>> results = new ArrayList<>();
        for (String reason : reasons) {
            results.add(pool.submit(() -> {
                try (Connection tx = db.connect()) {
                    barrier.await(10, TimeUnit.SECONDS);
                    return update(tx, """
                            INSERT INTO request_usage_compensations (request_id, credit_amount, reason, restored_daily)
                            VALUES (?, 1, ?, 1) ON CONFLICT (request_id) DO NOTHING
                            """, requestId, reason);
                }
            }));
        }
        int applied = 0;
        for (Future<Integer> f : results) {
            applied += f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(applied).isEqualTo(1);
        try (Connection check = db.connect()) {
            assertThat(count(check, "SELECT count(*) FROM request_usage_compensations WHERE request_id = ?", requestId)).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------
    // Verified Solution: insert-only · accepted coverage · 선형 revision
    // ------------------------------------------------------------------

    @Test
    void verifiedSolutionsAreInsertOnlyWithAcceptedCoverageAndLinearRevisions() throws SQLException {
        Turn turn = insertTurn(c, ALICE_NOTE);
        UUID runId = insertPendingRun(c, "req-solution-1", ALICE, ALICE_NOTE, turn);
        String direct = "[{\"claim_id\":\"claim-1\",\"coverage\":\"direct\"}]";
        String supporting = "[{\"claim_id\":\"claim-1\",\"coverage\":\"direct\"},{\"claim_id\":\"claim-2\",\"coverage\":\"supporting\"}]";

        UUID first = insertSolution(c, runId, turn.assistantMessageId(), "solution-1", direct, "direct", null);
        expectViolation(CHECK_VIOLATION, "ck_verified_solutions_claims", () -> insertSolution(c, runId,
                turn.assistantMessageId(), "solution-2", "[{\"claim_id\":\"claim-1\",\"coverage\":\"inconclusive\"}]", "direct", null));
        expectViolation(CHECK_VIOLATION, "ck_verified_solutions_claims", () -> insertSolution(c, runId,
                turn.assistantMessageId(), "solution-2", "[]", "direct", null));
        // coverage는 문자열 direct|supporting이어야 한다 (숫자·누락·비객체 claim 거부)
        for (String claims : List.of("[{\"claim_id\":\"claim-1\",\"coverage\":1}]", "[{\"claim_id\":\"claim-1\"}]", "[1]")) {
            expectViolation(CHECK_VIOLATION, "ck_verified_solutions_claims", () -> insertSolution(c, runId,
                    turn.assistantMessageId(), "solution-2", claims, "direct", null));
        }
        expectViolation(CHECK_VIOLATION, "ck_verified_solutions_overall_matches", () -> insertSolution(c, runId,
                turn.assistantMessageId(), "solution-2", supporting, "direct", null));

        expectViolation(TRIGGER_VIOLATION, "immutable", () -> update(c,
                "UPDATE verified_solutions SET solution_text = 'changed' WHERE solution_id = ?", first));

        UUID second = insertSolution(c, runId, turn.assistantMessageId(), "solution-2", supporting, "supporting", first);
        // 같은 solution을 두 번 정정할 수 없다 (분기·순환 방지)
        expectViolation(UNIQUE_VIOLATION, "ux_verified_solutions_single_revision", () -> insertSolution(c, runId,
                turn.assistantMessageId(), "solution-3", direct, "direct", first));
        // 후속이 아닌 row로 supersede할 수 없다
        expectViolation(TRIGGER_VIOLATION, "superseded_by", () -> update(c,
                "UPDATE verified_solutions SET status = 'SUPERSEDED', superseded_by_solution_id = ?, superseded_at = now() WHERE solution_id = ?",
                first, second));
        update(c, "UPDATE verified_solutions SET status = 'SUPERSEDED', superseded_by_solution_id = ?, superseded_at = now() WHERE solution_id = ?",
                second, first);
        expectViolation(TRIGGER_VIOLATION, "ACTIVE -> SUPERSEDED", () -> update(c,
                "UPDATE verified_solutions SET status = 'ACTIVE', superseded_by_solution_id = NULL, superseded_at = NULL WHERE solution_id = ?", first));
        // 다른 Note의 solution을 정정할 수 없다
        Turn bobTurn = insertTurn(c, BOB_NOTE);
        UUID bobRun = insertPendingRun(c, "req-solution-2", BOB, BOB_NOTE, bobTurn);
        expectViolation(FK_VIOLATION, "fk_verified_solutions_revises", () -> insertSolution(c, bobRun,
                bobTurn.assistantMessageId(), "solution-1", direct, "direct", second));
    }

    // ------------------------------------------------------------------
    // Turn Attachment media 규칙
    // ------------------------------------------------------------------

    @Test
    void turnAttachmentsBelongToUserMessagesWithValidatedMedia() throws SQLException {
        Turn turn = insertTurn(c, ALICE_NOTE);
        insertAttachment(c, turn.userMessageId(), 0, "application/pdf", 3, null, null, null);
        insertAttachment(c, turn.userMessageId(), 1, "image/png", null, 20, 10, 200L);
        expectViolation(CHECK_VIOLATION, "ck_turn_attachments_media",
                () -> insertAttachment(c, turn.userMessageId(), 2, "image/png", null, 20, 10, 999L));
        // NULL metadata로 media 규칙을 우회할 수 없다
        expectViolation(CHECK_VIOLATION, "ck_turn_attachments_media",
                () -> insertAttachment(c, turn.userMessageId(), 2, "application/pdf", null, null, null, null));
        expectViolation(CHECK_VIOLATION, "ck_turn_attachments_media",
                () -> insertAttachment(c, turn.userMessageId(), 2, "image/png", null, 20, 10, null));
        expectViolation(CHECK_VIOLATION, "ck_turn_attachments_media",
                () -> insertAttachment(c, turn.userMessageId(), 2, "image/png", null, null, 10, 200L));
        expectViolation(CHECK_VIOLATION, "ck_turn_attachments_mime",
                () -> insertAttachment(c, turn.userMessageId(), 2, "image/heic", null, 20, 10, 200L));
        expectViolation(UNIQUE_VIOLATION, "uq_turn_attachments_message_index",
                () -> insertAttachment(c, turn.userMessageId(), 0, "application/pdf", 1, null, null, null));
        // assistant 메시지에는 첨부를 달 수 없다
        expectViolation(FK_VIOLATION, "fk_turn_attachments_source_message",
                () -> insertAttachment(c, turn.assistantMessageId(), 0, "application/pdf", 1, null, null, null));
    }

    // ------------------------------------------------------------------
    // stale/deadline 조회 인덱스
    // ------------------------------------------------------------------

    @Test
    void staleLeaseDeadlineAndDispatchQueriesUsePartialIndexes() throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("SET LOCAL enable_seqscan = off");
        }
        assertThat(explain("SELECT run_id FROM ai_runs WHERE status IN ('RUNNING','RETRYING','CANCELING') AND lease_expires_at < now()"))
                .contains("idx_ai_runs_stale_lease");
        assertThat(explain("SELECT run_id FROM ai_runs WHERE status IN ('PENDING','RUNNING','RETRYING','CANCELING') AND request_deadline_at < now()"))
                .contains("idx_ai_runs_deadline");
        assertThat(explain("SELECT run_id FROM ai_runs WHERE status = 'PENDING' ORDER BY created_at LIMIT 10"))
                .contains("idx_ai_runs_pending_dispatch");
        assertThat(explain("SELECT run_id FROM ai_runs WHERE note_id = 2001 AND status IN ('PENDING','RUNNING','RETRYING','CANCELING')"))
                .contains("ux_ai_runs_note_active");
        assertThat(explain("SELECT seq FROM ai_run_events WHERE run_id = '00000000-0000-0000-0000-000000000000' AND seq > 3 ORDER BY seq"))
                .contains("uq_ai_run_events_seq");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }

    private void expectViolation(String sqlState, String messageFragment, SqlAction action) throws SQLException {
        Savepoint sp = c.setSavepoint();
        try {
            action.run();
            fail("expected SQLSTATE " + sqlState + " (" + messageFragment + ")");
        } catch (SQLException e) {
            assertThat(e.getSQLState()).as(e.getMessage()).isEqualTo(sqlState);
            assertThat(e.getMessage()).contains(messageFragment);
        } finally {
            c.rollback(sp);
        }
    }

    private String explain(String sql) throws SQLException {
        StringBuilder plan = new StringBuilder();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("EXPLAIN " + sql)) {
            while (rs.next()) {
                plan.append(rs.getString(1)).append('\n');
            }
        }
        return plan.toString();
    }

    private static long insertNote(Connection conn, long userId, String title) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO notes (user_id, title, created_at, updated_at) VALUES (?, ?, now(), now()) RETURNING note_id")) {
            ps.setLong(1, userId);
            ps.setString(2, title);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void completeAssistant(Connection conn, UUID messageId, String sourceReferences) throws SQLException {
        update(conn, """
                UPDATE chat_messages SET status = 'COMPLETED', completed_at = now(),
                       response_pages = '[{"response_page_id":"page-1","presentation":"main","result_blocks":[]}]',
                       source_references = ?::jsonb, plain_text = 'answer', content_sha256 = ?
                WHERE message_id = ?
                """, sourceReferences, "e".repeat(64), messageId);
    }

    private static void insertDependency(Connection conn, UUID runId, long noteId, long assetId, String kind)
            throws SQLException {
        update(conn, """
                INSERT INTO run_asset_dependencies (run_id, note_id, asset_id, dependency_kind, content_version)
                VALUES (?, ?, ?, ?, 1)
                """, runId, noteId, assetId, kind);
    }

    /** 1.80 규약대로 event seq를 ai_runs.last_event_seq에서 먼저 배정한다. */
    private static void allocateSeq(Connection conn, UUID runId, long seq) throws SQLException {
        update(conn, "UPDATE ai_runs SET last_event_seq = GREATEST(last_event_seq, ?) WHERE run_id = ?", seq, runId);
    }

    private static void insertServerEvent(Connection conn, UUID runId, long noteId, long seq, String type)
            throws SQLException {
        allocateSeq(conn, runId, seq);
        update(conn, "INSERT INTO ai_run_events (run_id, note_id, seq, event_type) VALUES (?, ?, ?, ?)",
                runId, noteId, seq, type);
    }

    private static int insertActivity(Connection conn, UUID runId, long seq, String sourceEventId) throws SQLException {
        allocateSeq(conn, runId, seq);
        return update(conn, """
                INSERT INTO ai_run_events (run_id, note_id, seq, event_type, source_event_id, attempt, activity_id, payload)
                SELECT ?, note_id, ?, 'activity.started', ?, 1, 'activity-1', '{"kind":"input_analysis"}'
                FROM ai_runs WHERE run_id = ?
                ON CONFLICT (run_id, source_event_id) DO NOTHING
                """, runId, seq, sourceEventId, runId);
    }

    private static void insertCharge(Connection conn, String requestId, long userId, UUID runId,
                                     int daily, int free, int paid) throws SQLException {
        update(conn, """
                INSERT INTO request_usage_charges (request_id, user_id, run_id, usage_units, credit_amount,
                                                   charged_daily, charged_free, charged_paid)
                VALUES (?, ?, ?, 1, ?, ?, ?, ?)
                """, requestId, userId, runId, daily + free + paid, daily, free, paid);
    }

    private static void insertChargeWithAmount(Connection conn, String requestId, long userId, int amount,
                                               int daily, int free, int paid) throws SQLException {
        update(conn, """
                INSERT INTO request_usage_charges (request_id, user_id, usage_units, credit_amount,
                                                   charged_daily, charged_free, charged_paid)
                VALUES (?, ?, 1, ?, ?, ?, ?)
                """, requestId, userId, amount, daily, free, paid);
    }

    private static void insertCompensation(Connection conn, String requestId, String reason, int amount)
            throws SQLException {
        update(conn, """
                INSERT INTO request_usage_compensations (request_id, credit_amount, reason, restored_daily)
                VALUES (?, ?, ?, ?)
                """, requestId, amount, reason, amount);
    }

    private static UUID insertSolution(Connection conn, UUID runId, UUID assistantMessageId, String publicId,
                                       String claims, String overall, UUID revises) throws SQLException {
        UUID id = UUID.randomUUID();
        update(conn, """
                INSERT INTO verified_solutions (solution_id, public_solution_id, note_id, source_run_id, assistant_message_id,
                       schema_version, solution_version, problem_statement, solution_text, claims, verification_summary,
                       verification_version, overall_coverage, content_sha256, revises_solution_id)
                SELECT ?, ?, note_id, ?, ?, 'verified-solution-v1', 1, 'x^2 = 4', 'x = ±2', ?::jsonb, '{}',
                       'chain-verification-report-v3', ?, ?, ?
                FROM ai_runs WHERE run_id = ?
                """, id, publicId, runId, assistantMessageId, claims, overall, "d".repeat(64), revises, runId);
        return id;
    }

    private static void insertAttachment(Connection conn, UUID messageId, int index, String mime, Integer pages,
                                         Integer width, Integer height, Long pixels) throws SQLException {
        update(conn, """
                INSERT INTO turn_attachments (attachment_id, note_id, source_message_id, attachment_index, object_key, sha256,
                       mime_type, byte_size, page_count, pixel_width, pixel_height, pixel_count,
                       upload_policy_version, page_understanding_version, image_normalization_version)
                SELECT gen_random_uuid(), note_id, ?, ?, 'turn/key', ?, ?, 100, ?, ?, ?, ?, 'upload-v1', 'pu-v1', 'img-v1'
                FROM chat_messages WHERE message_id = ?
                """, messageId, index, "a".repeat(64), mime, pages, width, height, pixels, messageId);
    }
}
