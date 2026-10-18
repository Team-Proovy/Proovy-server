package com.proovy.domain.run.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proovy.domain.asset.service.AssetsService;
import com.proovy.domain.storage.dto.request.BulkDeleteRequest;
import com.proovy.domain.storage.service.StorageService;
import com.proovy.domain.run.contract.PublicRunStatus;
import com.proovy.domain.run.entity.AiRunEventType;
import com.proovy.domain.run.event.RunEventStore;
import com.proovy.domain.run.event.StoredRunEvent;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.response.ErrorCode;
import com.proovy.support.db.PostgresTestDatabase;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 실제 Postgres(V1·V2 + seed)에서 production 접수 서비스를 검사한다.
 * <ul>
 *   <li>browser-run-v1 {@code admission/decisions.json} 판정표 전체를 production 판정 순서로 다시 돌리고, 각 case의
 *       부작용(Note·메시지·run·차감·binding·event 행 수와 잔액)을 확인한다.</li>
 *   <li>noteId 없는 같은 요청의 동시 재전송·같은 Note 동시 접수·중간 저장 실패 rollback·commit 뒤 run.created·asset 삭제 경합.</li>
 * </ul>
 * 사례마다 새 사용자를 만들어 rate limit·잔액 상태가 사례끼리 섞이지 않게 한다.
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
        "proovy.subscription.auto-renew-fixed-delay-ms=3600000",
        "proovy.run.admission.rate-limit.max-requests=3",
        "proovy.run.admission.rate-limit.window=30s"
})
class RunAdmissionPostgresTest {

    private static final int RATE_LIMIT = 3;
    private static final int UNIT = 10; // proovy.usage.credits-per-unit 기본값
    private static final String DECISIONS = "contracts/brain/browser-run-v1/admission/decisions.json";
    /** 인증 실패는 security filter(401)가 서비스 전에 끝낸다. */
    private static final Set<String> FILTER_CASES = Set.of("unauthenticated");

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

    @Autowired RunAdmissionService admission;
    @Autowired RunEventStore eventStore;
    @Autowired AssetsService assetsService;
    @Autowired StorageService storageService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    // ------------------------------------------------------------------
    // 판정표 (production 판정기)
    // ------------------------------------------------------------------

    @Test
    void decisionTableRunsThroughProductionAdmissionWithExpectedSideEffects() throws IOException {
        JsonNode table = loadDecisions();
        List<String> checked = new ArrayList<>();
        for (JsonNode decision : table.get("cases")) {
            String name = decision.get("name").asText();
            if (FILTER_CASES.contains(name)) {
                continue;
            }
            runCase(name, decision.get("state"), decision.get("expected"));
            checked.add(name);
        }
        Set<String> all = new HashSet<>();
        table.get("cases").forEach(decision -> all.add(decision.get("name").asText()));
        all.removeAll(FILTER_CASES);
        assertThat(checked).containsExactlyInAnyOrderElementsOf(all);
    }

    private void runCase(String name, JsonNode state, JsonNode expected) {
        long user = newUser();
        long other = newUser();
        String noteState = state.get("note").asText();
        Long note = switch (noteState) {
            case "owned" -> newNote(user, "ACTIVE");
            case "absent" -> null;
            case "missing" -> 9_000_000_000L;
            case "other_user" -> newNote(other, "ACTIVE");
            case "deleting" -> newNote(user, "DELETING");
            default -> throw new IllegalArgumentException(noteState);
        };

        ObjectNode body = body(note, "x^2-5x+6=0을 풀어줘");
        switch (state.get("references").asText()) {
            case "ok" -> { }
            case "invalid" -> {
                // 다른 Note의 asset을 Viewer Focus로 지정한다
                long elsewhere = newNote(user, "ACTIVE");
                body.putObject("viewerFocus").put("noteAssetId", String.valueOf(newPdf(user, elsewhere))).put("page", 1);
            }
            case "policy_exceeded" -> body.putArray("turnAttachmentIds").add(UUID.randomUUID().toString());
            default -> throw new IllegalArgumentException(state.get("references").asText());
        }

        JsonNode existing = state.get("existing");
        UUID existingRunId = null;
        if (!existing.isNull()) {
            // 같은 requestId로 먼저 접수된 run. 같은 사용자면 같은 body, 다른 사용자면 그 사용자의 Note에서.
            boolean sameUser = existing.get("sameUser").asBoolean();
            ObjectNode original = sameUser ? body.deepCopy() : body(newNote(other, "ACTIVE"), body.get("text").asText());
            original.put("requestId", body.get("requestId").asText());
            existingRunId = admission.admit(sameUser ? user : other, original).runId();
            setStatus(existingRunId, existing.get("runStatus").asText());
            if (!existing.get("sameFingerprint").asBoolean()) {
                body.put("text", body.get("text").asText() + " 다른 의미");
            }
        }

        JsonNode activeRun = state.get("activeRun");
        UUID activeRunId = existingRunId;
        if (!activeRun.isNull() && existing.isNull()) {
            long activeOwner = noteState.equals("other_user") ? other : user;
            RunAdmission active = admission.admit(activeOwner, body(note, "먼저 보낸 질문"));
            activeRunId = active.runId();
            setStatus(activeRunId, activeRun.get("status").asText());
        }

        if (state.get("rateLimited").asBoolean()) {
            long recent = count("SELECT count(*) FROM ai_runs WHERE user_id = ?", user);
            for (long i = recent; i < RATE_LIMIT; i++) {
                insertCompletedRun(user, newNote(user, "ACTIVE"));
            }
        }
        if (state.get("balance").asText().equals("insufficient")) {
            jdbc.update("UPDATE credit_balance SET daily_free_credit = 0, free_credit = 0, paid_credit = 0 "
                    + "WHERE user_id = ?", user);
        }
        switch (state.get("request").asText()) {
            case "valid" -> { }
            case "schema_invalid" -> body.put("conversationId", "123");
            case "unsupported" -> body.putArray("requestedOutputs").add("video");
            default -> throw new IllegalArgumentException(state.get("request").asText());
        }

        Map<String, Long> before = rows(user);
        String outcome = expected.get("outcome").asText();
        if (outcome.equals("error")) {
            Throwable thrown = catchThrowable(() -> admission.admit(user, body));
            assertThat(thrown).as(name).isInstanceOf(RunAdmissionRejectedException.class);
            RunAdmissionRejectedException rejected = (RunAdmissionRejectedException) thrown;
            assertThat(rejected.getErrorCode().getCode()).as(name).isEqualTo(expected.get("code").asText());
            assertThat(rejected.getErrorCode().getHttpStatus().value()).as(name).isEqualTo(expected.get("http").asInt());
            if (expected.has("result")) {
                assertThat(rejected.activeRun().orElseThrow().activeRunId()).as(name).isEqualTo(activeRunId.toString());
            } else {
                assertThat(rejected.activeRun()).as(name).isEmpty();
            }
            if (expected.has("headers")) {
                assertThat(rejected.retryAfterSeconds().orElseThrow()).as(name).isBetween(1L, 30L);
            } else {
                assertThat(rejected.retryAfterSeconds()).as(name).isEmpty();
            }
        } else {
            RunAdmission result = admission.admit(user, body);
            assertThat(RunAdmission.REPLAY_FROM_SEQ).isEqualTo(expected.get("replayFromSeq").asLong());
            if (outcome.equals("stream_replay")) {
                assertThat(result.outcome()).as(name).isEqualTo(RunAdmission.Outcome.REPLAYED);
                assertThat(result.runId()).as(name).isEqualTo(existingRunId);
                assertThat(result.thenLive()).as(name).isEqualTo(expected.get("thenLive").asBoolean());
            } else {
                assertThat(result.outcome()).as(name).isEqualTo(RunAdmission.Outcome.CREATED);
                assertThat(result.status()).isEqualTo(PublicRunStatus.PENDING);
                assertThat(result.thenLive()).isTrue();
            }
        }

        Map<String, Long> after = rows(user);
        if (expected.get("sideEffects").asText().equals("none")) {
            assertThat(after).as(name + " leaves no side effects").isEqualTo(before);
        } else {
            Map<String, Long> created = new LinkedHashMap<>(before);
            created.merge("notes", noteState.equals("absent") ? 1L : 0L, Long::sum);
            created.merge("messages", 2L, Long::sum);
            created.merge("runs", 1L, Long::sum);
            created.merge("charges", 1L, Long::sum);
            created.merge("bindings", 1L, Long::sum);
            created.merge("events", 1L, Long::sum);
            created.merge("credits", (long) -UNIT, Long::sum);
            assertThat(after).as(name + " creates one run").isEqualTo(created);
        }
    }

    // ------------------------------------------------------------------
    // 경합
    // ------------------------------------------------------------------

    @Test
    void concurrentRetransmissionsOfFirstQuestionWithoutNoteCreateOneNoteRunAndCharge() throws Exception {
        long user = newUser();
        ObjectNode body = body(null, "홈에서 보낸 첫 질문");
        Map<String, Long> before = rows(user);

        List<Object> results = race(6, () -> admission.admit(user, body.deepCopy()));

        Set<UUID> runs = new HashSet<>();
        Set<Long> notes = new HashSet<>();
        int created = 0;
        for (Object result : results) {
            RunAdmission admitted = (RunAdmission) result;
            runs.add(admitted.runId());
            notes.add(admitted.noteId());
            created += admitted.outcome() == RunAdmission.Outcome.CREATED ? 1 : 0;
        }
        assertThat(runs).hasSize(1);
        assertThat(notes).hasSize(1);
        assertThat(created).isEqualTo(1);
        Map<String, Long> after = rows(user);
        assertThat(after.get("notes")).isEqualTo(before.get("notes") + 1);
        assertThat(after.get("runs")).isEqualTo(before.get("runs") + 1);
        assertThat(after.get("messages")).isEqualTo(before.get("messages") + 2);
        assertThat(after.get("charges")).isEqualTo(before.get("charges") + 1);
        assertThat(after.get("events")).isEqualTo(before.get("events") + 1);
        assertThat(after.get("credits")).isEqualTo(before.get("credits") - UNIT);
    }

    @Test
    void concurrentDifferentRequestsOnOneNoteLeaveOneRunAndReturnActiveRunId() throws Exception {
        long user = newUser();
        long note = newNote(user, "ACTIVE");
        Map<String, Long> before = rows(user);
        AtomicInteger seq = new AtomicInteger();

        List<Object> results = race(5, () -> admission.admit(user, body(note, "질문 " + seq.incrementAndGet())));

        List<RunAdmission> accepted = new ArrayList<>();
        List<RunAdmissionRejectedException> conflicts = new ArrayList<>();
        for (Object result : results) {
            if (result instanceof RunAdmission admitted) {
                accepted.add(admitted);
            } else {
                conflicts.add((RunAdmissionRejectedException) result);
            }
        }
        assertThat(accepted).hasSize(1);
        assertThat(conflicts).hasSize(4).allSatisfy(conflict -> {
            assertThat(conflict.getErrorCode()).isEqualTo(ErrorCode.RUN4091);
            assertThat(conflict.activeRun().orElseThrow().activeRunId())
                    .isEqualTo(accepted.get(0).runId().toString());
        });
        assertThat(count("SELECT count(*) FROM chat_messages WHERE note_id = ?", note)).isEqualTo(2);
        Map<String, Long> after = rows(user);
        assertThat(after.get("runs")).isEqualTo(before.get("runs") + 1);
        assertThat(after.get("charges")).isEqualTo(before.get("charges") + 1);
        assertThat(after.get("credits")).isEqualTo(before.get("credits") - UNIT);
    }

    @Test
    void concurrentSameRequestOnExistingNoteReplaysTheWinner() throws Exception {
        long user = newUser();
        long note = newNote(user, "ACTIVE");
        ObjectNode body = body(note, "같은 Note 재전송");

        List<Object> results = race(5, () -> admission.admit(user, body.deepCopy()));

        assertThat(results).allSatisfy(result -> assertThat(result).isInstanceOf(RunAdmission.class));
        assertThat(results.stream().map(result -> ((RunAdmission) result).runId()).distinct()).hasSize(1);
        assertThat(count("SELECT count(*) FROM ai_runs WHERE note_id = ?", note)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM request_usage_charges WHERE user_id = ?", user)).isEqualTo(1);
    }

    @Test
    void concurrentFirstQuestionsOnDifferentNotesChargeEachWithoutLostUpdate() throws Exception {
        long user = newUser();
        long credits = rows(user).get("credits");

        List<Object> results = race(3, () -> admission.admit(user, body(null, "새 질문 " + UUID.randomUUID())));

        assertThat(results).allSatisfy(result -> assertThat(result).isInstanceOf(RunAdmission.class));
        assertThat(rows(user).get("credits")).isEqualTo(credits - 3L * UNIT);
        assertThat(count("SELECT count(*) FROM notes WHERE user_id = ?", user)).isEqualTo(3);
    }

    // ------------------------------------------------------------------
    // 거부·rollback·event
    // ------------------------------------------------------------------

    @Test
    void requestIdReuseByOtherUserIsRejectedWithoutRevealingTheRun() {
        long owner = newUser();
        long intruder = newUser();
        ObjectNode body = body(newNote(owner, "ACTIVE"), "내 질문");
        admission.admit(owner, body);

        ObjectNode reuse = body(newNote(intruder, "ACTIVE"), "내 질문");
        reuse.put("requestId", body.get("requestId").asText());
        Map<String, Long> before = rows(intruder);

        assertThatThrownBy(() -> admission.admit(intruder, reuse))
                .isInstanceOfSatisfying(RunAdmissionRejectedException.class, rejected -> {
                    assertThat(rejected.getErrorCode()).isEqualTo(ErrorCode.RUN4092);
                    assertThat(rejected.activeRun()).isEmpty();
                    assertThat(rejected.getMessage()).isEqualTo(ErrorCode.RUN4092.getMessage());
                });
        // 남의 Note ID로 body를 맞춰 보내도 같은 거부다(상태·noteId 비노출)
        ObjectNode copy = body.deepCopy();
        assertThatThrownBy(() -> admission.admit(intruder, copy))
                .isInstanceOfSatisfying(RunAdmissionRejectedException.class,
                        rejected -> assertThat(rejected.getErrorCode()).isEqualTo(ErrorCode.RUN4092));
        assertThat(rows(intruder)).isEqualTo(before);
    }

    @Test
    void replayDoesNotChargeAgainAndReturnsTheSameIdentifiers() {
        long user = newUser();
        ObjectNode body = body(null, "  재전송 확인  ");
        RunAdmission first = admission.admit(user, body);
        Map<String, Long> before = rows(user);

        // 양끝 공백만 다른 같은 의미 재전송
        ObjectNode resend = body.deepCopy().put("text", "재전송 확인");
        RunAdmission again = admission.admit(user, resend);

        assertThat(again.outcome()).isEqualTo(RunAdmission.Outcome.REPLAYED);
        assertThat(again).usingRecursiveComparison().ignoringFields("outcome").isEqualTo(first);
        assertThat(rows(user)).isEqualTo(before);
        // run.created.noteId를 넣어 재전송하면 다른 의미다
        ObjectNode withNote = body.deepCopy().put("noteId", String.valueOf(first.noteId()));
        assertThatThrownBy(() -> admission.admit(user, withNote))
                .isInstanceOfSatisfying(RunAdmissionRejectedException.class,
                        rejected -> assertThat(rejected.getErrorCode()).isEqualTo(ErrorCode.RUN4092));
    }

    @Test
    void failureLateInTheTransactionRollsBackNoteMessagesRunChargeAndEvent() {
        long user = newUser();
        ObjectNode body = body(null, "중간 실패");
        String requestId = body.get("requestId").asText();
        jdbc.execute("""
                CREATE FUNCTION t179_fail_binding() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.request_id = '%s' THEN RAISE EXCEPTION 'injected binding failure'; END IF;
                    RETURN NEW;
                END; $$""".formatted(requestId));
        jdbc.execute("CREATE TRIGGER t179_fail_binding BEFORE INSERT ON run_request_bindings "
                + "FOR EACH ROW EXECUTE FUNCTION t179_fail_binding()");
        Map<String, Long> before = rows(user);
        try {
            assertThatThrownBy(() -> admission.admit(user, body)).isInstanceOf(DataAccessException.class);
        } finally {
            jdbc.execute("DROP TRIGGER t179_fail_binding ON run_request_bindings");
            jdbc.execute("DROP FUNCTION t179_fail_binding()");
        }
        assertThat(rows(user)).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM request_usage_charges WHERE request_id = ?", requestId)).isZero();

        // 같은 requestId로 다시 보내면 새로 접수된다(실패는 binding을 남기지 않았다)
        assertThat(admission.admit(user, body).outcome()).isEqualTo(RunAdmission.Outcome.CREATED);
    }

    @Test
    void deterministicStorageFailureIsNotRetriedAndLeavesNothing() {
        long user = newUser();
        Map<String, Long> before = rows(user);

        // Postgres text·jsonb는 U+0000을 받지 않는다(22P05) — 결정적 실패라 재시도하지 않는다
        Throwable thrown = catchThrowable(() -> admission.admit(user, body(null, "NUL 포함\u0000")));

        assertThat(thrown).isNotNull().isNotInstanceOf(RunAdmissionRejectedException.class);
        assertThat(RunAdmissionService.isRetryable(thrown)).isFalse();
        assertThat(rows(user)).isEqualTo(before);
    }

    @Test
    void contentVersionChangedBeforeDependencyLockIsRejectedAsReferenceUnavailable() {
        long user = newUser();
        long note = newNote(user, "ACTIVE");
        long pdf = newPdf(user, note);
        // snapshot이 version 1을 읽은 뒤, 의존성 잠금 전에 재처리가 version을 올린 상황
        jdbc.execute("""
                CREATE FUNCTION t179_bump_version() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    UPDATE assets SET content_version = content_version + 1 WHERE id = NEW.asset_id;
                    RETURN NEW;
                END; $$""");
        jdbc.execute("CREATE TRIGGER t179_bump_version BEFORE INSERT ON run_asset_dependencies "
                + "FOR EACH ROW EXECUTE FUNCTION t179_bump_version()");
        Map<String, Long> before = rows(user);
        try {
            assertThatThrownBy(() -> admission.admit(user, body(note, "재처리와 겹친 질문")))
                    .isInstanceOfSatisfying(RunAdmissionRejectedException.class,
                            rejected -> assertThat(rejected.getErrorCode()).isEqualTo(ErrorCode.RUN4221));
        } finally {
            jdbc.execute("DROP TRIGGER t179_bump_version ON run_asset_dependencies");
            jdbc.execute("DROP FUNCTION t179_bump_version()");
        }
        assertThat(rows(user)).isEqualTo(before);
        assertThat(count("SELECT content_version FROM assets WHERE id = ?", pdf)).isEqualTo(1);
    }

    @Test
    void insufficientBalanceLeavesNoNoteOrMessage() {
        long user = newUser();
        jdbc.update("UPDATE credit_balance SET daily_free_credit = 9, free_credit = 0, paid_credit = 0 WHERE user_id = ?",
                user);
        Map<String, Long> before = rows(user);

        assertThatThrownBy(() -> admission.admit(user, body(null, "잔액 부족")))
                .isInstanceOfSatisfying(RunAdmissionRejectedException.class,
                        rejected -> assertThat(rejected.getErrorCode()).isEqualTo(ErrorCode.RUN4021));
        assertThat(rows(user)).isEqualTo(before);
    }

    @Test
    void acceptedRunIsPendingWithSnapshotAndCommittedRunCreatedAtSeqOne() throws Exception {
        long user = newUser();
        long note = newNote(user, "ACTIVE");
        long pdf = newPdf(user, note);
        ObjectNode body = body(note, "3쪽 문제 풀어줘");
        body.putArray("requestedTasks").add("solve");
        body.putObject("viewerFocus").put("noteAssetId", String.valueOf(pdf)).put("page", 3);

        RunAdmission admitted = admission.admit(user, body);

        // 다른 연결(별도 transaction)에서 바로 보인다 = commit 뒤 소비 가능
        try (Connection c = db.connect();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT r.status, r.attempt, r.last_event_seq, r.request_snapshot ->> 'request_id',
                            e.seq, e.event_type, e.payload::text, u.created_at < a.created_at,
                            (SELECT count(*) FROM run_asset_dependencies d WHERE d.run_id = r.run_id)
                     FROM ai_runs r
                     JOIN ai_run_events e ON e.run_id = r.run_id
                     JOIN chat_messages u ON u.message_id = r.user_message_id
                     JOIN chat_messages a ON a.message_id = r.assistant_message_id
                     WHERE r.run_id = ?
                     """)) {
            ps.setObject(1, admitted.runId());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("PENDING");
                assertThat(rs.getInt(2)).isZero();
                assertThat(rs.getLong(3)).isEqualTo(1);
                assertThat(rs.getString(4)).isEqualTo(body.get("requestId").asText());
                assertThat(rs.getLong(5)).isEqualTo(1);
                assertThat(rs.getString(6)).isEqualTo("run.created");
                JsonNode payload = mapper.readTree(rs.getString(7));
                assertThat(payload.get("requestId").asText()).isEqualTo(admitted.requestId());
                assertThat(payload.get("noteId").asText()).isEqualTo(String.valueOf(note));
                assertThat(payload.get("userMessageId").asText()).isEqualTo(admitted.userMessageId().toString());
                assertThat(payload.get("assistantMessageId").asText())
                        .isEqualTo(admitted.assistantMessageId().toString());
                assertThat(payload.get("status").asText()).isEqualTo("PENDING");
                assertThat(payload.get("requestedTasks")).isEqualTo(mapper.readTree("[\"solve\"]"));
                assertThat(payload.get("requestedOutputs")).isEqualTo(mapper.readTree("[]"));
                assertThat(rs.getBoolean(8)).isTrue();
                assertThat(rs.getLong(9)).isEqualTo(2); // corpus + viewer focus
                assertThat(rs.next()).isFalse();
            }
        }
        List<StoredRunEvent> events = eventStore.findAfter(admitted.runId(), 0, 10);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(AiRunEventType.RUN_CREATED);
            assertThat(event.cursor().toString()).isEqualTo(admitted.runId() + ":1");
        });
        // 응답·snapshot 어디에도 사용자 JWT·ChatSession/thread ID가 없다
        String row = jdbc.queryForObject("SELECT to_jsonb(r)::text FROM ai_runs r WHERE run_id = ?", String.class,
                admitted.runId());
        assertThat(row).doesNotContain("authToken", "auth_token", "thread_id", "session_id", "Bearer");
    }

    // ------------------------------------------------------------------
    // asset 삭제 경합 (#227 R5)
    // ------------------------------------------------------------------

    @Test
    void assetUsedByARunCannotBeHardDeletedAndUnusedAssetCan() {
        long user = newUser();
        long note = newNote(user, "ACTIVE");
        long used = newPdf(user, note);
        admission.admit(user, body(note, "자료 기반 질문"));
        long otherNote = newNote(user, "ACTIVE");
        long unused = newPdf(user, otherNote);

        assertThatThrownBy(() -> assetsService.deleteAsset(user, used))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ASSET4092));
        assertThat(count("SELECT count(*) FROM assets WHERE id = ?", used)).isEqualTo(1);

        assetsService.deleteAsset(user, unused);
        assertThat(count("SELECT count(*) FROM assets WHERE id = ?", unused)).isZero();
    }

    @Test
    void bulkDeletionRefusesWhenAnyAssetWasUsedByARun() {
        long user = newUser();
        long note = newNote(user, "ACTIVE");
        long used = newPdf(user, note);
        admission.admit(user, body(note, "일괄 삭제 전 질문"));
        long otherNote = newNote(user, "ACTIVE");
        long unused = newPdf(user, otherNote);

        assertThatThrownBy(() -> storageService.bulkDeleteAssets(user, new BulkDeleteRequest(List.of(unused, used))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ASSET4092));
        assertThat(count("SELECT count(*) FROM assets WHERE id IN (?, ?)", used, unused)).isEqualTo(2);

        // 의존 이력이 없으면 지워진다(GCS 삭제는 commit 뒤 — 테스트 endpoint 실패는 로그만 남는다)
        storageService.bulkDeleteAssets(user, new BulkDeleteRequest(List.of(unused)));
        assertThat(count("SELECT count(*) FROM assets WHERE id = ?", unused)).isZero();
    }

    @Test
    void assetDeletionWaitsForAdmissionHoldingTheNoteLockThenRefuses() throws Exception {
        long user = newUser();
        long note = newNote(user, "ACTIVE");
        long pdf = newPdf(user, note);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection admitting = db.connect()) {
            // 진행 중인 접수: Note 잠금 → PENDING run·의존성 insert (commit 전)
            admitting.setAutoCommit(false);
            try (PreparedStatement ps = admitting.prepareStatement("SELECT 1 FROM notes WHERE note_id = ? FOR UPDATE")) {
                ps.setLong(1, note);
                ps.executeQuery().close();
            }
            UUID runId = UUID.randomUUID();
            insertPendingRunWithDependency(admitting, user, note, runId, pdf);

            Future<Throwable> deletion = pool.submit(() -> {
                try {
                    assetsService.deleteAsset(user, pdf);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            Thread.sleep(500);
            assertThat(deletion.isDone()).as("deletion waits for the admission's note lock").isFalse();
            admitting.commit();

            assertThat(deletion.get(20, TimeUnit.SECONDS)).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ASSET4092));
            assertThat(count("SELECT count(*) FROM assets WHERE id = ?", pdf)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void admissionWaitsForAssetDeletionHoldingTheNoteLockAndSnapshotsTheRemainingCorpus() throws Exception {
        long user = newUser();
        long note = newNote(user, "ACTIVE");
        long deleted = newPdf(user, note);
        long kept = newPdf(user, note);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection deleting = db.connect()) {
            // 진행 중인 삭제: Note 잠금 → asset 삭제 (commit 전)
            deleting.setAutoCommit(false);
            try (PreparedStatement ps = deleting.prepareStatement("SELECT 1 FROM notes WHERE note_id = ? FOR UPDATE")) {
                ps.setLong(1, note);
                ps.executeQuery().close();
            }
            try (PreparedStatement ps = deleting.prepareStatement("DELETE FROM assets WHERE id = ?")) {
                ps.setLong(1, deleted);
                ps.executeUpdate();
            }
            Future<RunAdmission> admitted = pool.submit(() -> admission.admit(user, body(note, "삭제와 겹친 질문")));
            Thread.sleep(500);
            assertThat(admitted.isDone()).as("admission waits for the deletion's note lock").isFalse();
            deleting.commit();

            UUID runId = admitted.get(20, TimeUnit.SECONDS).runId();
            assertThat(jdbc.queryForList("SELECT asset_id FROM run_asset_dependencies WHERE run_id = ?", Long.class,
                    runId)).containsExactly(kept);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void insertPendingRunWithDependency(Connection c, long user, long note, UUID runId, long asset)
            throws Exception {
        UUID userMessage = UUID.randomUUID();
        UUID assistantMessage = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO chat_messages (message_id, note_id, role, status, user_input, plain_text)
                VALUES (?, ?, 'USER', 'COMPLETED', '{"text":"q"}', 'q'), (?, ?, 'ASSISTANT', 'PENDING', NULL, NULL);
                INSERT INTO ai_runs (run_id, request_id, user_id, note_id, user_message_id, assistant_message_id,
                                     request_fingerprint, request_snapshot, policy_snapshot, budget_policy_version,
                                     request_deadline_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, '{}', '{}', 'budget-v1', now() + interval '3 minutes');
                INSERT INTO run_asset_dependencies (run_id, note_id, asset_id, dependency_kind, content_version)
                VALUES (?, ?, ?, 'CORPUS_SNAPSHOT', 1)
                """)) {
            Object[] args = {userMessage, note, assistantMessage, note,
                    runId, "req-" + runId, user, note, userMessage, assistantMessage, "f".repeat(64),
                    runId, note, asset};
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.execute();
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private List<Object> race(int threads, Callable<RunAdmission> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return task.call();
                    } catch (RunAdmissionRejectedException e) {
                        return e;
                    }
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private ObjectNode body(Long noteId, String text) {
        ObjectNode body = mapper.createObjectNode();
        body.put("requestId", UUID.randomUUID().toString());
        if (noteId != null) {
            body.put("noteId", String.valueOf(noteId));
        }
        body.put("text", text);
        return body;
    }

    private Map<String, Long> rows(long user) {
        Map<String, Long> rows = new LinkedHashMap<>();
        rows.put("notes", count("SELECT count(*) FROM notes WHERE user_id = ?", user));
        rows.put("messages", count("SELECT count(*) FROM chat_messages m JOIN notes n ON n.note_id = m.note_id "
                + "WHERE n.user_id = ?", user));
        rows.put("runs", count("SELECT count(*) FROM ai_runs WHERE user_id = ?", user));
        rows.put("charges", count("SELECT count(*) FROM request_usage_charges WHERE user_id = ?", user));
        rows.put("bindings", count("SELECT count(*) FROM run_request_bindings WHERE user_id = ?", user));
        rows.put("events", count("SELECT count(*) FROM ai_run_events e JOIN ai_runs r ON r.run_id = e.run_id "
                + "WHERE r.user_id = ?", user));
        rows.put("credits", count("SELECT daily_free_credit + free_credit + paid_credit FROM credit_balance "
                + "WHERE user_id = ?", user));
        return rows;
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private long newUser() {
        String key = UUID.randomUUID().toString();
        long user = jdbc.queryForObject("""
                INSERT INTO users (nickname, provider, provider_user_id, email, created_at, updated_at)
                VALUES ('t179', 'KAKAO', ?, ?, now(), now()) RETURNING user_id
                """, Long.class, key, key + "@example.test");
        jdbc.update("""
                INSERT INTO credit_balance (user_id, daily_free_credit, daily_free_limit, daily_expires_at, free_credit,
                                            paid_credit, created_at, updated_at)
                VALUES (?, 100, 100, now() + interval '1 day', 0, 0, now(), now())
                """, user);
        return user;
    }

    private long newNote(long user, String status) {
        return jdbc.queryForObject("""
                INSERT INTO notes (user_id, title, status, deletion_requested_at, created_at, updated_at)
                VALUES (?, 'admission note', ?, CASE WHEN ? = 'DELETING' THEN now() END, now(), now())
                RETURNING note_id
                """, Long.class, user, status, status);
    }

    private long newPdf(long user, long note) {
        return jdbc.queryForObject("""
                INSERT INTO assets (user_id, note_id, file_name, file_size, mime_type, object_key, source, status,
                                    content_version, sha256, page_count, created_at, updated_at, version)
                VALUES (?, ?, 'f.pdf', 2048, 'application/pdf', 'k', 'upload', 'ACTIVE', 1, ?, 12, now(), now(), 0)
                RETURNING id
                """, Long.class, user, note, "a".repeat(64));
    }

    /** rate limit을 채우는 이전 run(COMPLETED, 다른 Note). */
    private UUID insertCompletedRun(long user, long note) {
        UUID runId = UUID.randomUUID();
        UUID userMessage = UUID.randomUUID();
        UUID assistantMessage = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO chat_messages (message_id, note_id, role, status, user_input, plain_text, created_at)
                VALUES (?, ?, 'USER', 'COMPLETED', '{"text":"이전 질문"}', '이전 질문', now()),
                       (?, ?, 'ASSISTANT', 'PENDING', NULL, NULL, now() + interval '1 microsecond')
                """, userMessage, note, assistantMessage, note);
        jdbc.update("""
                INSERT INTO ai_runs (run_id, request_id, user_id, note_id, user_message_id, assistant_message_id, status,
                                     attempt, fence_token, started_at, request_fingerprint, request_snapshot,
                                     policy_snapshot, budget_policy_version, request_deadline_at,
                                     response_disposition, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, 'COMPLETED', 1, 'fence-1', now(), ?, '{}', '{}', 'budget-v1', now(),
                        'substantive', now())
                """, runId, "req-" + runId, user, note, userMessage, assistantMessage, "f".repeat(64));
        return runId;
    }

    private void setStatus(UUID runId, String status) {
        if (status.equals("PENDING")) {
            return;
        }
        jdbc.update("""
                UPDATE ai_runs SET status = 'RUNNING', attempt = 1, fence_token = 'fence-1', started_at = now(),
                       lease_owner = 'coordinator-1', lease_expires_at = now() + interval '30 seconds'
                WHERE run_id = ?
                """, runId);
        switch (status) {
            case "RUNNING" -> { }
            case "CANCELING" -> jdbc.update("""
                    UPDATE ai_runs SET status = 'CANCELING', cancel_cause = 'user_requested',
                           cancel_requested_from = 'RUNNING', cancel_requested_at = now()
                    WHERE run_id = ?
                    """, runId);
            case "COMPLETED" -> jdbc.update("""
                    UPDATE ai_runs SET status = 'COMPLETED', response_disposition = 'substantive', completed_at = now(),
                           lease_owner = NULL, lease_expires_at = NULL
                    WHERE run_id = ?
                    """, runId);
            default -> throw new IllegalArgumentException(status);
        }
    }

    private JsonNode loadDecisions() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(DECISIONS)) {
            if (in == null) {
                throw new IllegalStateException("missing " + DECISIONS);
            }
            return mapper.readTree(in);
        }
    }
}
