package com.proovy.domain.run.snapshot;

import static com.proovy.support.db.ProductRows.ALICE;
import static com.proovy.support.db.ProductRows.BOB;
import static com.proovy.support.db.ProductRows.BOB_NOTE;
import static com.proovy.support.db.ProductRows.BOB_PDF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.proovy.domain.run.brain.ExecutionUsage;
import com.proovy.domain.run.brain.HistoryMessage;
import com.proovy.domain.run.brain.HistoryRole;
import com.proovy.domain.run.brain.InternalRunRequest;
import com.proovy.domain.run.brain.SolutionReferenceStatus;
import com.proovy.domain.run.brain.TurnAttachmentRef;
import com.proovy.domain.run.brain.VerifiedSolutionRef;
import com.proovy.domain.run.contract.ConversationRunRequest;
import com.proovy.domain.run.contract.PageSelection;
import com.proovy.domain.run.contract.ViewerFocus;
import com.proovy.domain.run.entity.AiRun;
import com.proovy.domain.run.repository.AiRunRepository;
import com.proovy.domain.run.repository.RunAssetDependencyRepository;
import com.proovy.global.response.ErrorCode;
import com.proovy.support.db.PostgresTestDatabase;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 실제 Postgres(V1 baseline + seed)에서 snapshot 구성 SQL과 저장·복원을 검사한다.
 * 접수 뒤 history·solution 상태·corpus·설정이 바뀌어도 저장 run에서 복원한 전달 요청은 처음과 같고,
 * 저장 행에는 grant·read URL이 남지 않는다.
 */
@Tag("postgres")
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "gcs.project-id=proovy-test",
        "gcs.endpoint=http://127.0.0.1:1",
        "jwt.secret=test-jwt-secret-test-jwt-secret-0123456789",
        "oauth.kakao.client-id=test", "oauth.kakao.client-secret=test",
        "oauth.naver.client-id=test", "oauth.naver.client-secret=test",
        "openrouter.api.key=test",
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1",
        "spring.task.scheduling.pool.size=1",
        "proovy.subscription.plan-transition-fixed-delay-ms=3600000",
        "proovy.subscription.auto-renew-fixed-delay-ms=3600000"
})
class RunSnapshotPostgresTest {

    private static final AtomicLong CLOCK = new AtomicLong();
    private static final Instant BASE = Instant.parse("2026-10-17T00:00:00Z");
    private static final String GRANT = "grant-plaintext-should-never-persist";
    private static final String VIEWER_URL = "https://storage.example/viewer?X-Goog-Signature=never-persist";

    private static PostgresTestDatabase db;

    @BeforeAll
    static void createDatabase() {
        db = PostgresTestDatabase.createMigrated();
        db.runScript("db/seed/product-seed.sql");
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> db.jdbcUrl());
        registry.add("spring.datasource.username", PostgresTestDatabase::adminUser);
        registry.add("spring.datasource.password", PostgresTestDatabase::adminPassword);
    }

    @AfterAll
    static void dropDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @Autowired RunSnapshotFactory factory;
    @Autowired AiRunRepository aiRunRepository;
    @Autowired RunAssetDependencyRepository dependencyRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    // ------------------------------------------------------------------
    // 저장·복원
    // ------------------------------------------------------------------

    @Test
    void storedSnapshotRestoresTheSameRequestAfterHistorySolutionsCorpusAndAttemptsChange() {
        long note = newNote(ALICE);
        long pdf = newPdf(ALICE, note, 12);
        Turn first = completedTurn(note, "x^2-5x+6=0 풀어줘", "인수분해하면 (x-2)(x-3)=0 이므로 근은 2와 3이다.");
        UUID solutionA = solution(note, first, "solution-aaaaaaaaaaaaaaaaaaaa-001", null);
        UUID runId = UUID.randomUUID();
        ConversationRunRequest request = request(note, "다른 방법으로 설명해줘",
                new ViewerFocus(String.valueOf(pdf), 3, new PageSelection(0.125, 0.25, 0.875, 0.5)));
        Instant now = Instant.now();

        RunSnapshot snapshot = admit(runId, note, request, now);
        InternalRunRequest admitted = RunSnapshotFactoryTest.firstDelivery(snapshot,
                new RunAssetAccess(null, VIEWER_URL, Map.of()));

        // --- 접수 뒤 세계가 바뀐다: 후속 turn, 정정으로 A가 superseded, 새 asset ---
        Turn later = completedTurn(note, "다른 질문", "다른 답");
        UUID solutionB = solution(note, later, "solution-bbbbbbbbbbbbbbbbbbbb-001", solutionA);
        jdbc.update("""
                UPDATE verified_solutions SET status = 'SUPERSEDED', superseded_by_solution_id = ?, superseded_at = now()
                WHERE solution_id = ?
                """, solutionB, solutionA);
        newPdf(ALICE, note, 3);
        // 지금 같은 입력으로 새로 만들면 다른 history·corpus가 고정된다
        RunSnapshot fresh = factory.create(UUID.randomUUID(), ALICE, note, request, Instant.now());
        assertThat(fresh.requestFingerprint()).isNotEqualTo(snapshot.requestFingerprint());

        // --- coordinator가 attempt 1을 배정하고(재시작 후처럼) DB에서 다시 읽어 전달한다 ---
        jdbc.update("""
                UPDATE ai_runs SET status = 'RUNNING', attempt = 1, fence_token = 'fence-1', started_at = now(),
                       lease_owner = 'coordinator-1', lease_expires_at = now() + interval '30 seconds'
                WHERE run_id = ?
                """, runId);
        InternalRunRequest delivered = deliver(runId);

        assertThat(delivered.fingerprint().sha256()).isEqualTo(snapshot.requestFingerprint())
                .isEqualTo(admitted.fingerprint().sha256())
                .isEqualTo(jdbc.queryForObject("SELECT request_fingerprint FROM ai_runs WHERE run_id = ?",
                        String.class, runId));
        assertThat(semantic(delivered)).isEqualTo(semantic(admitted));
        assertThat(delivered.history()).extracting(HistoryMessage::messageId).containsExactly(
                first.userMessageId().toString(), first.assistantMessageId().toString());
        assertThat(delivered.verifiedSolutionRefs()).extracting(VerifiedSolutionRef::solutionId,
                        VerifiedSolutionRef::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("solution-aaaaaaaaaaaaaaaaaaaa-001",
                        SolutionReferenceStatus.ACTIVE)); // 전송 시점 상태
        assertThat(delivered.viewerContext().page()).isEqualTo(3);
        assertThat(delivered.viewerContext().readUrl()).isEqualTo(VIEWER_URL);
        assertThat(delivered.retrievalScope().corpusVersion()).isEqualTo(admitted.retrievalScope().corpusVersion());
        assertThat(delivered.executionOptions().usage()).isEqualTo(ExecutionUsage.ZERO);
        assertThat(delivered.attempt()).isEqualTo(1);
        assertThat(delivered.assetAccessGrant()).isEqualTo(GRANT);

        // --- 다음 attempt: 누적 usage는 run 행에서, deadline은 그대로 ---
        jdbc.update("""
                UPDATE ai_runs SET status = 'RETRYING', attempt = 2, fence_token = 'fence-2', used_llm_calls = 5,
                       used_tool_calls = 2, used_output_tokens = 4000, used_cost_micros = 7000, used_page_understandings = 1
                WHERE run_id = ?
                """, runId);
        InternalRunRequest retried = deliver(runId);
        assertThat(retried.attempt()).isEqualTo(2);
        assertThat(retried.fenceToken()).isEqualTo("fence-2");
        assertThat(retried.executionOptions().usage()).isEqualTo(new ExecutionUsage(5, 2, 4000, 7000, 1));
        assertThat(retried.executionOptions().requestDeadlineAt())
                .isEqualTo(admitted.executionOptions().requestDeadlineAt());
        assertThat(retried.fingerprint()).isEqualTo(delivered.fingerprint());
        assertThat(retried.executionOptions().requestDeadlineAt().toInstant())
                .isEqualTo(now.plus(RunSnapshotFactoryTest.defaults().requestTimeout()).truncatedTo(ChronoUnit.MICROS));

        // DB가 deadline 연장·usage 감소·snapshot 교체를 막는다(V1 guard)
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE ai_runs SET request_deadline_at = request_deadline_at + interval '1 hour' WHERE run_id = ?",
                runId)).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("UPDATE ai_runs SET used_llm_calls = 0 WHERE run_id = ?", runId))
                .hasMessageContaining("monotonic");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE ai_runs SET request_snapshot = request_snapshot || '{\"history\":[]}' WHERE run_id = ?",
                runId)).hasMessageContaining("immutable");

        // 저장 행 어디에도 grant·read URL·control 값이 없다
        String row = jdbc.queryForObject("SELECT to_jsonb(r)::text FROM ai_runs r WHERE run_id = ?", String.class,
                runId);
        assertThat(row).doesNotContain(GRANT, VIEWER_URL, "never-persist", "read_url", "asset_access_grant",
                RunSnapshotFactory.UNISSUED_READ_URL);
        assertThat(jdbc.queryForObject("SELECT request_snapshot::text FROM ai_runs WHERE run_id = ?", String.class,
                runId)).doesNotContain("fence", "\"attempt\"", "\"usage\"");
        assertThat(jdbc.queryForObject("SELECT grant_digest FROM ai_runs WHERE run_id = ?", String.class, runId))
                .isNull();

        // 의존성은 접수 시점 corpus(asset 1개)와 Viewer Focus
        assertThat(jdbc.queryForList("""
                SELECT asset_id, dependency_kind, content_version FROM run_asset_dependencies WHERE run_id = ?
                ORDER BY dependency_kind
                """, runId)).containsExactly(
                Map.of("asset_id", pdf, "dependency_kind", "CORPUS_SNAPSHOT", "content_version", 1),
                Map.of("asset_id", pdf, "dependency_kind", "VIEWER_FOCUS", "content_version", 1));
    }

    @Test
    void historyFromTheDatabaseKeepsOnlyCompletedTurnsWithinTheTokenBound() {
        long note = newNote(ALICE);
        Turn oldest = completedTurn(note, "가장 오래된 질문", "가".repeat(2_000));
        attachment(note, oldest.userMessageId());
        List<Turn> turns = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            turns.add(completedTurn(note, "질문 " + i, "나".repeat(1_900)));
        }
        failedTurn(note, "실패한 질문 FAILED-TURN");
        canceledTurn(note, "취소한 질문 CANCELED-TURN");
        Turn newest = completedTurn(note, "마지막 질문", "마지막 답");
        UUID recentAttachment = attachment(note, newest.userMessageId());

        RunSnapshot snapshot = admit(UUID.randomUUID(), note, request(note, "그 다음 질문", null), Instant.now());
        InternalRunRequest delivered = RunSnapshotFactoryTest.firstDelivery(snapshot, RunAssetAccess.NONE);

        int tokens = delivered.history().stream().mapToInt(message -> HistoryBound.estimateTokens(message.text()))
                .sum();
        assertThat(tokens).isLessThanOrEqualTo(24_000);
        assertThat(snapshot.policySnapshot().at("/history/estimated_tokens").asInt()).isEqualTo(tokens);
        assertThat(snapshot.policySnapshot().at("/history/truncated").asBoolean()).isTrue();
        List<String> ids = delivered.history().stream().map(HistoryMessage::messageId).toList();
        assertThat(ids).doesNotContain(oldest.userMessageId().toString(), oldest.assistantMessageId().toString());
        assertThat(ids.subList(ids.size() - 2, ids.size())).containsExactly(newest.userMessageId().toString(),
                newest.assistantMessageId().toString());
        assertThat(delivered.history()).noneMatch(message -> message.text().contains("FAILED-TURN")
                || message.text().contains("CANCELED-TURN") || message.text().equals("그 다음 질문"));
        // role이 run 기준 짝으로 번갈아 나온다(잘린 앞쪽은 assistant로 시작할 수 있다)
        for (int i = 1; i < delivered.history().size(); i++) {
            assertThat(delivered.history().get(i).role()).isNotEqualTo(delivered.history().get(i - 1).role());
        }
        assertThat(delivered.history().get(delivered.history().size() - 1).role()).isEqualTo(HistoryRole.ASSISTANT);
        assertThat(delivered.turnAttachmentRefs()).extracting(TurnAttachmentRef::attachmentId)
                .containsExactly(recentAttachment.toString());
    }

    @Test
    void aLongNewestAnswerIsKeptWholeWithItsSolutionReference() {
        long note = newNote(ALICE);
        Turn older = completedTurn(note, "이전 질문", "이전 답");
        solution(note, older, "solution-cccccccccccccccccccc-001", null);
        Turn proof = completedTurn(note, "증명해줘", "증".repeat(30_000));
        solution(note, proof, "solution-dddddddddddddddddddd-001", null);

        RunSnapshot snapshot = admit(UUID.randomUUID(), note, request(note, "그 풀이 3단계 다시 설명해줘", null),
                Instant.now());
        InternalRunRequest delivered = RunSnapshotFactoryTest.firstDelivery(snapshot, RunAssetAccess.NONE);

        assertThat(delivered.history()).extracting(HistoryMessage::messageId)
                .containsExactly(proof.assistantMessageId().toString());
        assertThat(delivered.history().get(0).text()).hasSize(30_000);
        assertThat(delivered.verifiedSolutionRefs()).extracting(VerifiedSolutionRef::solutionId)
                .containsExactly("solution-dddddddddddddddddddd-001");
        assertThat(snapshot.policySnapshot().at("/history/estimated_tokens").asInt()).isEqualTo(30_008);
    }

    // ------------------------------------------------------------------
    // 거부
    // ------------------------------------------------------------------

    @Test
    void foreignOrDeletingNotesAndUnusableAssetsAreRejected() {
        long note = newNote(ALICE);
        assertRejected(ALICE, BOB_NOTE, request(BOB_NOTE, "q", null), ErrorCode.RUN4041);
        assertRejected(BOB, note, request(note, "q", null), ErrorCode.RUN4041);
        assertRejected(ALICE, 999_999L, request(999_999L, "q", null), ErrorCode.RUN4041);

        // 다른 Note(타인)의 asset, 같은 사용자의 다른 Note asset, 범위 밖 page, 이미지(pixel 사실 없음), 비ACTIVE
        long otherNote = newNote(ALICE);
        long otherNotePdf = newPdf(ALICE, otherNote, 5);
        long pdf = newPdf(ALICE, note, 4);
        long png = jdbc.queryForObject("""
                INSERT INTO assets (user_id, note_id, file_name, file_size, mime_type, object_key, source, status,
                                    sha256, created_at, updated_at)
                VALUES (?, ?, 'p.png', 100, 'image/png', 'k', 'upload', 'ACTIVE', ?, now(), now()) RETURNING id
                """, Long.class, ALICE, note, "c".repeat(64));
        long pending = jdbc.queryForObject("""
                INSERT INTO assets (user_id, note_id, file_name, file_size, mime_type, object_key, source, status,
                                    created_at, updated_at)
                VALUES (?, ?, 'u.pdf', 100, 'application/pdf', 'k', 'upload', 'PENDING', now(), now()) RETURNING id
                """, Long.class, ALICE, note);
        for (String asset : List.of(String.valueOf(BOB_PDF), String.valueOf(otherNotePdf), String.valueOf(png),
                String.valueOf(pending), "asset-55", "0")) {
            assertRejected(ALICE, note, request(note, "q", new ViewerFocus(asset, 1, null)), ErrorCode.RUN4221);
        }
        assertRejected(ALICE, note, request(note, "q", new ViewerFocus(String.valueOf(pdf), 5, null)),
                ErrorCode.RUN4221);
        assertRejected(ALICE, note, new ConversationRunRequest(UUID.randomUUID().toString(), String.valueOf(note),
                "q", null, null, null, List.of(UUID.randomUUID().toString()), null), ErrorCode.RUN4221);

        jdbc.update("UPDATE notes SET status = 'DELETING', deletion_requested_at = now() WHERE note_id = ?", note);
        assertRejected(ALICE, note, request(note, "q", null), ErrorCode.RUN4041);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_runs WHERE note_id = ?", Long.class, note)).isZero();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private record Turn(UUID userMessageId, UUID assistantMessageId, UUID runId) {
    }

    /** 1.79 접수 transaction 흉내: 메시지 → snapshot → run → 의존성. */
    private RunSnapshot admit(UUID runId, long note, ConversationRunRequest request, Instant now) {
        return tx.execute(status -> {
            RunSnapshot snapshot = factory.create(runId, ALICE, note, request, now);
            UUID userMessage = UUID.randomUUID();
            UUID assistantMessage = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO chat_messages (message_id, note_id, role, status, user_input, plain_text, created_at)
                    VALUES (?, ?, 'USER', 'COMPLETED', jsonb_build_object('text', ?::text), ?, ?),
                           (?, ?, 'ASSISTANT', 'PENDING', NULL, NULL, ?)
                    """, userMessage, note, request.text(), request.text(), tick(), assistantMessage, note, tick());
            AiRun run = aiRunRepository.save(snapshot.toPendingRun(userMessage, assistantMessage, now));
            aiRunRepository.flush();
            dependencyRepository.saveAll(snapshot.assetDependencies(now));
            dependencyRepository.flush();
            return snapshot;
        });
    }

    private InternalRunRequest deliver(UUID runId) {
        AiRun run = tx.execute(status -> aiRunRepository.findById(runId).orElseThrow());
        return RunSnapshotRestorer.delivery(run, new RunAssetAccess(GRANT, VIEWER_URL, Map.of()));
    }

    /** control 값을 뺀 의미 문서. */
    private static String semantic(InternalRunRequest request) {
        return RunSnapshotCodec.strip(request).toString();
    }

    private void assertRejected(long user, long note, ConversationRunRequest request, ErrorCode code) {
        assertThatThrownBy(() -> factory.create(UUID.randomUUID(), user, note, request, Instant.now()))
                .isInstanceOf(RunSnapshotRejectedException.class)
                .extracting(e -> ((RunSnapshotRejectedException) e).getErrorCode()).isEqualTo(code);
    }

    private static ConversationRunRequest request(long note, String text, ViewerFocus focus) {
        return new ConversationRunRequest(UUID.randomUUID().toString(), String.valueOf(note), text, null, null, null,
                null, focus);
    }

    private static java.sql.Timestamp tick() {
        return java.sql.Timestamp.from(BASE.plusSeconds(CLOCK.incrementAndGet()));
    }

    private long newNote(long user) {
        return jdbc.queryForObject("""
                INSERT INTO notes (user_id, title, created_at, updated_at) VALUES (?, 'snapshot note', now(), now())
                RETURNING note_id
                """, Long.class, user);
    }

    private long newPdf(long user, long note, int pages) {
        return jdbc.queryForObject("""
                INSERT INTO assets (user_id, note_id, file_name, file_size, mime_type, object_key, source, status,
                                    content_version, sha256, page_count, created_at, updated_at)
                VALUES (?, ?, 'f.pdf', 2048, 'application/pdf', 'k', 'upload', 'ACTIVE', 1, ?, ?, now(), now())
                RETURNING id
                """, Long.class, user, note, "a".repeat(64), pages);
    }

    private Turn completedTurn(long note, String question, String answer) {
        Turn turn = insertTurn(note, question, "COMPLETED", answer);
        jdbc.update("""
                INSERT INTO ai_runs (run_id, request_id, user_id, note_id, user_message_id, assistant_message_id, status,
                                     attempt, fence_token, started_at, request_fingerprint, request_snapshot,
                                     policy_snapshot, budget_policy_version, request_deadline_at,
                                     response_disposition, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, 'COMPLETED', 1, 'fence-1', now(), ?, '{}', '{}', 'budget-v1', now(),
                        'substantive', now())
                """, turn.runId(), "req-" + turn.runId(), ALICE, note, turn.userMessageId(), turn.assistantMessageId(),
                "f".repeat(64));
        return turn;
    }

    private void failedTurn(long note, String question) {
        Turn turn = insertTurn(note, question, "FAILED", null);
        jdbc.update("""
                INSERT INTO ai_runs (run_id, request_id, user_id, note_id, user_message_id, assistant_message_id, status,
                                     attempt, fence_token, started_at, request_fingerprint, request_snapshot,
                                     policy_snapshot, budget_policy_version, request_deadline_at,
                                     failure_code, failure_class, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, 'FAILED', 1, 'fence-1', now(), ?, '{}', '{}', 'budget-v1', now(),
                        'verification_failed', 'verification', now())
                """, turn.runId(), "req-" + turn.runId(), ALICE, note, turn.userMessageId(), turn.assistantMessageId(),
                "f".repeat(64));
    }

    private void canceledTurn(long note, String question) {
        Turn turn = insertTurn(note, question, "CANCELED", null);
        jdbc.update("""
                INSERT INTO ai_runs (run_id, request_id, user_id, note_id, user_message_id, assistant_message_id, status,
                                     attempt, fence_token, started_at, request_fingerprint, request_snapshot,
                                     policy_snapshot, budget_policy_version, request_deadline_at,
                                     cancel_cause, cancel_requested_from, cancel_requested_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, 'CANCELED', 1, 'fence-1', now(), ?, '{}', '{}', 'budget-v1', now(),
                        'user_requested', 'RUNNING', now(), now())
                """, turn.runId(), "req-" + turn.runId(), ALICE, note, turn.userMessageId(), turn.assistantMessageId(),
                "f".repeat(64));
    }

    private Turn insertTurn(long note, String question, String assistantStatus, String answer) {
        Turn turn = new Turn(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("""
                INSERT INTO chat_messages (message_id, note_id, role, status, user_input, plain_text, created_at)
                VALUES (?, ?, 'USER', 'COMPLETED', jsonb_build_object('text', ?::text), ?, ?)
                """, turn.userMessageId(), note, question, question, tick());
        if (answer == null) {
            jdbc.update("""
                    INSERT INTO chat_messages (message_id, note_id, role, status, created_at, completed_at)
                    VALUES (?, ?, 'ASSISTANT', ?, ?, now())
                    """, turn.assistantMessageId(), note, assistantStatus, tick());
        } else {
            jdbc.update("""
                    INSERT INTO chat_messages (message_id, note_id, role, status, response_pages, source_references,
                                               plain_text, content_sha256, created_at, completed_at)
                    VALUES (?, ?, 'ASSISTANT', 'COMPLETED', '[{"response_page_id":"page-1"}]', '[]', ?, ?, ?, now())
                    """, turn.assistantMessageId(), note, answer, "e".repeat(64), tick());
        }
        return turn;
    }

    private UUID solution(long note, Turn turn, String publicId, UUID revises) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO verified_solutions (solution_id, public_solution_id, note_id, source_run_id, assistant_message_id,
                       schema_version, solution_version, problem_statement, solution_text, claims, verification_summary,
                       verification_version, overall_coverage, content_sha256, revises_solution_id)
                VALUES (?, ?, ?, ?, ?, 'verified-solution-v1', 1, 'x^2-5x+6=0의 근', 'SOLUTION-BODY x = 2, 3',
                        '[{"claim_id":"claim-1","statement":"근은 2와 3이다","coverage":"direct"}]', '{}',
                        'chain-verification-report-v3', 'direct', ?, ?)
                """, id, publicId, note, turn.runId(), turn.assistantMessageId(), "d".repeat(64), revises);
        return id;
    }

    private UUID attachment(long note, UUID userMessageId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO turn_attachments (attachment_id, note_id, source_message_id, attachment_index, object_key,
                       sha256, mime_type, byte_size, pixel_width, pixel_height, pixel_count,
                       upload_policy_version, page_understanding_version, image_normalization_version, display_label)
                VALUES (?, ?, ?, 0, 'turn/key', ?, 'image/png', 100, 10, 10, 100, 'upload-v1', 'pu-v1', 'img-v1', '사진')
                """, id, note, userMessageId, "b".repeat(64));
        return id;
    }
}
