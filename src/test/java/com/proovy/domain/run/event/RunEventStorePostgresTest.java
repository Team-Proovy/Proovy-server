package com.proovy.domain.run.event;

import static com.proovy.domain.run.event.SourceActivitiesContractTest.envelope;
import static com.proovy.domain.run.event.SourceActivitiesContractTest.snake;
import static com.proovy.support.db.ProductRows.ALICE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proovy.domain.run.brain.InternalRunWire;
import com.proovy.domain.run.brain.SourceEvent;
import com.proovy.domain.run.contract.ServerFrameType;
import com.proovy.domain.run.entity.AiRun;
import com.proovy.domain.run.entity.AiRunEventType;
import com.proovy.domain.run.event.ActivityAppendResult.Outcome;
import com.proovy.domain.run.repository.AiRunRepository;
import com.proovy.support.db.PostgresTestDatabase;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 실제 Postgres에서 durable event 저장소를 검사한다: fence·상태 수락, source_event_id dedup, run별 seq 배정,
 * 동시 append·rollback·terminal 경합, commit 후 알림, replay 조회와 browser-run-v1 stream 재현.
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
class RunEventStorePostgresTest {

    private static final Instant T0 = Instant.parse("2026-10-17T09:00:00Z");

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

    @Autowired RunEventStore store;
    @Autowired RunEventSignals signals;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AiRunRepository aiRunRepository;

    // ------------------------------------------------------------------
    // dedup·seq
    // ------------------------------------------------------------------

    @Test
    @DisplayName("같은 source_event_id 재전달은 row·seq·last_event_seq를 늘리지 않는다")
    void redeliveryDoesNotGrowRowsOrSeq() {
        UUID run = runningRun();
        SourceEvent started = started(run, "activity-1", "input_analysis", "analyzing_input");

        assertThat(store.appendActivity(started)).isEqualTo(new ActivityAppendResult(Outcome.APPENDED, 2));
        for (int i = 0; i < 3; i++) {
            assertThat(store.appendActivity(started)).isEqualTo(new ActivityAppendResult(Outcome.DUPLICATE, 2));
        }
        assertThat(rows(run)).isEqualTo(2);
        assertThat(store.lastSeq(run)).hasValue(2);
    }

    @Test
    @DisplayName("128자를 넘는 source_event_id(brain-source-v1 상한 256)도 결정적 digest 키로 저장·dedup한다")
    void longSourceEventIdIsDeduplicated() {
        UUID run = runningRun();
        ObjectNode envelope = envelope(run.toString(), "activity.started",
                base("activity-1", "input_analysis").put("label", "analyzing_input"));
        envelope.put("source_event_id", run + ":" + "a".repeat(200));
        SourceEvent event = source(envelope);

        assertThat(store.appendActivity(event)).isEqualTo(new ActivityAppendResult(Outcome.APPENDED, 2));
        assertThat(store.appendActivity(event)).isEqualTo(new ActivityAppendResult(Outcome.DUPLICATE, 2));
        assertThat(jdbc.queryForObject("SELECT source_event_id FROM ai_run_events WHERE run_id = ? AND seq = 2",
                String.class, run)).matches("sha256:[0-9a-f]{64}");
    }

    @Test
    @DisplayName("같은 source_event_id가 다른 내용으로 오면 기존 row를 유지하고 CONFLICTING_DUPLICATE")
    void conflictingDuplicateKeepsOriginal() {
        UUID run = runningRun();
        store.appendActivity(started(run, "activity-1", "input_analysis", "analyzing_input"));
        SourceEvent first = updated(run, "activity-1", "input_analysis", 1, "입력을 읽고 있다");
        assertThat(store.appendActivity(first).outcome()).isEqualTo(Outcome.APPENDED);

        SourceEvent changed = updated(run, "activity-1", "input_analysis", 1, "다른 문구");
        assertThat(store.appendActivity(changed)).isEqualTo(new ActivityAppendResult(Outcome.CONFLICTING_DUPLICATE, 3));
        assertThat(store.findAfter(run, 2, 10)).singleElement()
                .satisfies(event -> assertThat(event.payload().get("message").asText()).isEqualTo("입력을 읽고 있다"));
        assertThat(store.lastSeq(run)).hasValue(3);
    }

    @Test
    @DisplayName("동시 append: 서로 다른 event는 빈틈없는 seq를, 같은 event의 동시 재전달은 한 row만 남긴다")
    void concurrentAppendsGetContiguousSeqsAndOneRowPerSource() throws Exception {
        UUID run = runningRun();
        int activities = 6;
        int deliveriesEach = 4;
        List<SourceEvent> events = new ArrayList<>();
        for (int i = 1; i <= activities; i++) {
            events.add(started(run, "activity-" + i, "note_retrieval", "searching_notes"));
        }
        List<SourceEvent> deliveries = new ArrayList<>();
        for (int d = 0; d < deliveriesEach; d++) {
            deliveries.addAll(events);
        }
        Collections.shuffle(deliveries);

        List<ActivityAppendResult> results = race(deliveries.size(), i -> () -> store.appendActivity(deliveries.get(i)));

        assertThat(results).filteredOn(r -> r.outcome() == Outcome.APPENDED).hasSize(activities);
        assertThat(results).filteredOn(r -> r.outcome() == Outcome.DUPLICATE).hasSize(activities * (deliveriesEach - 1));
        assertThat(seqs(run)).containsExactlyElementsOf(range(1, 1 + activities));
        assertThat(store.lastSeq(run)).hasValue(1 + activities);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT source_event_id) FROM ai_run_events WHERE run_id = ?",
                Long.class, run)).isEqualTo(activities);
    }

    @Test
    @DisplayName("rollback된 append는 row·seq·알림을 남기지 않고 다음 append가 같은 seq를 받는다")
    void rollbackLeavesNoSeqOrSignal() {
        UUID run = runningRun();
        List<Long> signalled = new CopyOnWriteArrayList<>();
        try (RunEventSignals.Subscription ignored = signals.subscribe(run, (id, seq) -> signalled.add(seq))) {
            SourceEvent started = started(run, "activity-1", "input_analysis", "analyzing_input");
            tx.executeWithoutResult(status -> {
                assertThat(store.appendActivity(started).seq()).isEqualTo(2);
                status.setRollbackOnly();
            });
            assertThat(rows(run)).isEqualTo(1);
            assertThat(store.lastSeq(run)).hasValue(1);
            assertThat(signalled).isEmpty();

            assertThat(store.appendActivity(started)).isEqualTo(new ActivityAppendResult(Outcome.APPENDED, 2));
            assertThat(signalled).containsExactly(2L);
        }
    }

    // ------------------------------------------------------------------
    // 영속성 context의 AiRun (r180 #1)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("같은 transaction의 managed AiRun을 바꿔도 append가 올린 last_event_seq가 되써지지 않는다(Activity)")
    void managedRunDoesNotWriteBackStaleSeqAfterActivity() {
        UUID run = runningRun();
        tx.executeWithoutResult(status -> {
            AiRun managed = aiRunRepository.findById(run).orElseThrow();
            assertThat(store.appendActivity(started(run, "activity-1", "input_analysis", "analyzing_input")).seq())
                    .isEqualTo(2);
            assertThat(managed.getLastEventSeq()).isEqualTo(2);
            // 후속 태스크가 AiRun에 변경 메서드를 두는 경우를 흉내 낸다: 전체 컬럼 UPDATE가 일어난다
            touch(managed);
        });
        assertThat(store.lastSeq(run)).hasValue(2);
        assertThat(jdbc.queryForObject("SELECT heartbeat_at FROM ai_runs WHERE run_id = ?", OffsetDateTime.class,
                run).toInstant()).isEqualTo(TOUCHED);
    }

    @Test
    @DisplayName("접수 흐름: JPA로 persist한 AiRun → run.created → AiRun 변경 → commit이 seq 1을 유지한다")
    void admissionWithJpaRunKeepsCreatedSeq() {
        UUID run = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            long noteId = newNote();
            UUID[] turn = insertTurn(noteId);
            AiRun pending = aiRunRepository.save(AiRun.pending().id(run).requestId(UUID.randomUUID().toString())
                    .userId(ALICE).noteId(noteId).userMessageId(turn[0]).assistantMessageId(turn[1])
                    .requestFingerprint("f".repeat(64)).requestSnapshot(json("{\"input\":{}}"))
                    .policySnapshot(json("{\"limits\":{}}")).budgetPolicyVersion("budget-v1")
                    .requestDeadlineAt(Instant.now().plusSeconds(600)).createdAt(Instant.now()).build());
            assertThat(store.appendServerEvent(run, ServerFrameType.RUN_CREATED, runCreated(), T0)).isEqualTo(1);
            touch(pending);
        });
        assertThat(store.lastSeq(run)).hasValue(1);
        assertThat(seqs(run)).containsExactly(1L);
    }

    // ------------------------------------------------------------------
    // fence·상태
    // ------------------------------------------------------------------

    @Test
    @DisplayName("이전 attempt·다른 fence·아직 없는 attempt의 Activity는 저장되지 않는다")
    void staleFenceRejected() {
        UUID run = runningRun();
        store.appendActivity(started(run, "activity-1", "solution_generation", "drafting_solution"));
        // 재시도: RUNNING → RETRYING → RUNNING(attempt 2, fence-2)
        jdbc.update("UPDATE ai_runs SET status = 'RETRYING' WHERE run_id = ?", run);
        jdbc.update("""
                UPDATE ai_runs SET status = 'RUNNING', attempt = 2, fence_token = 'fence-2', retry_count = 1
                WHERE run_id = ?
                """, run);
        long before = store.lastSeq(run).orElseThrow();

        SourceEvent late = event(run, 1, "fence-1", "activity.updated",
                base("activity-1", "solution_generation").put("status", "in_progress"), "r1");
        SourceEvent forgedFence = event(run, 2, "fence-x", "activity.updated",
                base("activity-1", "solution_generation").put("status", "in_progress"), "r2");
        SourceEvent future = event(run, 3, "fence-3", "activity.updated",
                base("activity-1", "solution_generation").put("status", "in_progress"), "r3");
        for (SourceEvent stale : List.of(late, forgedFence, future)) {
            ActivityAppendResult result = store.appendActivity(stale);
            assertThat(result.outcome()).isEqualTo(Outcome.STALE_FENCE);
            assertThat(result.isStreamObsolete()).isTrue();
        }
        assertThat(store.lastSeq(run)).hasValue(before);

        // 새 attempt는 같은 Activity를 이어 갈 수 있다(transient 재시도는 같은 activity_id)
        SourceEvent current = event(run, 2, "fence-2", "activity.updated",
                base("activity-1", "solution_generation").put("status", "retrying"), "r4");
        assertThat(store.appendActivity(current).outcome()).isEqualTo(Outcome.APPENDED);
        assertThat(jdbc.queryForObject("SELECT attempt FROM ai_run_events WHERE run_id = ? AND seq = ?",
                Integer.class, run, before + 1)).isEqualTo(2);
    }

    @Test
    @DisplayName("CANCELING은 열린 Activity의 activity.canceled만 받고, terminal 뒤에는 아무것도 받지 않는다")
    void cancelingAndTerminalAcceptance() {
        UUID run = runningRun();
        store.appendActivity(started(run, "activity-1", "input_analysis", "analyzing_input"));
        store.appendActivity(event(run, 1, "fence-1", "activity.completed", base("activity-1", "input_analysis"), "r9"));
        store.appendActivity(started(run, "activity-2", "note_retrieval", "searching_notes"));
        tx.executeWithoutResult(status -> {
            cancelRunning(run);
            store.appendServerEvent(run, ServerFrameType.RUN_CANCELING,
                    Map.of("assistantMessageId", UUID.randomUUID().toString(), "status", "CANCELING"), T0);
        });
        long canceling = store.lastSeq(run).orElseThrow();

        assertThat(store.appendActivity(started(run, "activity-3", "verification", "verifying_steps",
                "SymPy")).outcome()).isEqualTo(Outcome.NOT_ACCEPTING);
        assertThat(store.appendActivity(event(run, 1, "fence-1", "activity.completed",
                base("activity-2", "note_retrieval").put("match_status", "matched"), "r1")).outcome())
                .isEqualTo(Outcome.NOT_ACCEPTING);
        // 이미 끝난 Activity의 canceled는 순서 위반
        assertThat(store.appendActivity(event(run, 1, "fence-1", "activity.canceled",
                base("activity-1", "input_analysis"), "r10")).outcome()).isEqualTo(Outcome.OUT_OF_ORDER);
        // 진행 중이던 Activity의 wind-down 표식은 받는다
        ActivityAppendResult canceled = store.appendActivity(event(run, 1, "fence-1", "activity.canceled",
                base("activity-2", "note_retrieval"), "r2"));
        assertThat(canceled).isEqualTo(new ActivityAppendResult(Outcome.APPENDED, canceling + 1));

        long last = tx.execute(status -> {
            long allocated = jdbc.queryForObject("""
                    UPDATE ai_runs SET status = 'CANCELED', completed_at = now(), lease_owner = NULL,
                           lease_expires_at = NULL, last_event_seq = last_event_seq + 1
                    WHERE run_id = ? AND status = 'CANCELING' RETURNING last_event_seq
                    """, Long.class, run);
            store.appendTerminalEvents(run, allocated, List.of(TerminalRunEvent.runCanceled(json(
                    "{\"assistantMessageId\":\"" + UUID.randomUUID() + "\",\"status\":\"CANCELED\"}"))), T0);
            return allocated;
        });
        assertThat(store.appendActivity(event(run, 1, "fence-1", "activity.canceled",
                base("activity-9", "input_analysis"), "r0")).outcome()).isEqualTo(Outcome.NOT_ACCEPTING);
        assertThat(store.lastSeq(run)).hasValue(last);
        assertThat(seqs(run)).containsExactlyElementsOf(range(1, last));
    }

    @Test
    @DisplayName("PENDING run(attempt 0)과 없는 run의 Activity는 저장되지 않는다")
    void pendingAndUnknownRuns() {
        UUID pending = pendingRunWithCreated();
        assertThat(store.appendActivity(started(pending, "activity-1", "input_analysis", "analyzing_input"))
                .outcome()).isEqualTo(Outcome.STALE_FENCE);
        assertThat(store.appendActivity(started(UUID.randomUUID(), "activity-1", "input_analysis", "analyzing_input"))
                .outcome()).isEqualTo(Outcome.UNKNOWN_RUN);
        assertThat(rows(pending)).isEqualTo(1);
    }

    @Test
    @DisplayName("Activity 순서: started 없는 후속·두 번째 started·kind 불일치·terminal 뒤 이벤트는 저장하지 않는다")
    void activityOrder() {
        UUID run = runningRun();
        assertThat(store.appendActivity(updated(run, "activity-1", "input_analysis", 1, "읽는 중")).outcome())
                .isEqualTo(Outcome.OUT_OF_ORDER);
        store.appendActivity(started(run, "activity-1", "input_analysis", "analyzing_input"));
        assertThat(store.appendActivity(event(run, 1, "fence-1", "activity.started",
                base("activity-1", "input_analysis").put("label", "reading_input"), "r5")).outcome())
                .isEqualTo(Outcome.OUT_OF_ORDER);
        assertThat(store.appendActivity(event(run, 1, "fence-1", "activity.updated",
                base("activity-1", "solution_generation"), "r1")).outcome()).isEqualTo(Outcome.OUT_OF_ORDER);
        assertThat(store.appendActivity(event(run, 1, "fence-1", "activity.failed",
                base("activity-1", "input_analysis").put("code", "timeout"), "r2")).outcome())
                .isEqualTo(Outcome.APPENDED);
        assertThat(store.appendActivity(updated(run, "activity-1", "input_analysis", 3, "다시")).outcome())
                .isEqualTo(Outcome.OUT_OF_ORDER);
        assertThat(rows(run)).isEqualTo(3);
    }

    @Test
    @DisplayName("계약 위반·terminal source는 MALFORMED로 버리고 금지 내용이 DB에 남지 않는다")
    void malformedSourceIsDroppedWithoutLeak() throws Exception {
        UUID run = runningRun();
        for (String fixture : new String[]{"code-field-in-payload", "stdout-in-payload", "verification-answer-leak",
                "verification-step-verdict", "fake-percent", "url-in-message"}) {
            ObjectNode raw = (ObjectNode) ActivityFixtures.read(
                    ActivityFixtures.FIXTURES.resolve("invalid/" + fixture + ".json"));
            raw.put("run_id", run.toString());
            raw.put("fence_token", "fence-1");
            raw.put("source_event_id", run + ":" + fixture);
            assertThat(store.appendActivity(source(raw))).as(fixture)
                    .isEqualTo(ActivityAppendResult.of(Outcome.MALFORMED));
        }
        ObjectNode terminal = envelope(run.toString(), "activity.started", JsonNodeFactory.instance.objectNode());
        terminal.put("type", "run.completed");
        assertThat(store.appendActivity(source(terminal)).outcome()).isEqualTo(Outcome.MALFORMED);

        assertThat(rows(run)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ai_run_events WHERE payload::text LIKE '%DO-NOT-LEAK%'", Long.class)).isZero();
    }

    @Test
    @DisplayName("heartbeat는 저장 event type이 아니다(저장·seq 경로가 없다)")
    void heartbeatIsNotAnEventType() {
        assertThat(AiRunEventType.values()).noneMatch(type -> type.wireValue().contains("heartbeat"));
        assertThat(ServerFrameType.of("heartbeat")).isEmpty();
    }

    // ------------------------------------------------------------------
    // Server 소유·terminal
    // ------------------------------------------------------------------

    @Test
    @DisplayName("run.created는 접수 transaction의 PENDING run 첫 event(seq 1)로 한 번만, transaction 없이는 거부")
    void runCreatedOnlyFirst() {
        UUID run = tx.execute(status -> {
            UUID id = insertPendingRun();
            assertThat(store.appendServerEvent(id, ServerFrameType.RUN_CREATED, runCreated(), T0)).isEqualTo(1);
            return id;
        });
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                store.appendServerEvent(run, ServerFrameType.RUN_CREATED, runCreated(), T0)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.appendServerEvent(run, ServerFrameType.RUN_CREATED, runCreated(), T0))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                store.appendServerEvent(run, ServerFrameType.RESPONSE_PAGE_STARTED, runCreated(), T0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(seqs(run)).containsExactly(1L);
        assertThat(store.findAfter(run, 0, 10).get(0).payload().get("status").asText()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("run.created 실패는 접수 transaction 전체를 rollback한다(run·event가 남지 않는다)")
    void admissionRollbackLeavesNothing() {
        UUID run = UUID.randomUUID();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            insertPendingRun(run, newNote());
            store.appendServerEvent(run, ServerFrameType.RUN_CREATED, runCreated(), T0);
            throw new IllegalStateException("later admission step failed");
        })).hasMessage("later admission step failed");
        assertThat(store.lastSeq(run)).isEmpty();
        assertThat(rows(run)).isZero();
    }

    @Test
    @DisplayName("terminal batch: 상태에 맞는 terminal이 마지막, page 쌍은 completed에만, 배정된 seq 범위만 쓴다")
    void terminalBatchValidation() {
        UUID run = runningRun();
        // terminal 전이 전에는 거부
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> store.appendTerminalEvents(run, 2,
                List.of(TerminalRunEvent.runFailed(json("{}"))), T0))).isInstanceOf(IllegalStateException.class);

        ObjectNode page0 = json("{\"responsePageId\":\"page-1\",\"pageIndex\":0}");
        ObjectNode page1 = json("{\"responsePageId\":\"page-2\",\"pageIndex\":1}");
        List<List<TerminalRunEvent>> broken = List.of(
                List.of(TerminalRunEvent.runFailed(json("{}"))),
                List.of(TerminalRunEvent.pageStarted(page0), TerminalRunEvent.runCompleted(json("{}"))),
                List.of(TerminalRunEvent.pageStarted(page0), TerminalRunEvent.pageCompleted(page1),
                        TerminalRunEvent.runCompleted(json("{}"))),
                List.of(TerminalRunEvent.pageStarted(page1), TerminalRunEvent.pageCompleted(page1),
                        TerminalRunEvent.runCompleted(json("{}"))),
                List.of(TerminalRunEvent.pageCompleted(page0), TerminalRunEvent.pageStarted(page0),
                        TerminalRunEvent.runCompleted(json("{}"))));
        for (List<TerminalRunEvent> batch : broken) {
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                long allocated = complete(run, batch.size());
                store.appendTerminalEvents(run, allocated, batch, T0);
            })).isInstanceOf(IllegalStateException.class);
            assertThat(store.lastSeq(run)).hasValue(1);
        }
        // 배정 수와 batch 크기가 다르면 거부
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            long allocated = complete(run, 3);
            store.appendTerminalEvents(run, allocated, List.of(TerminalRunEvent.runCompleted(json("{}"))), T0);
        })).isInstanceOf(IllegalStateException.class);

        List<TerminalRunEvent> valid = List.of(TerminalRunEvent.pageStarted(page0), TerminalRunEvent.pageCompleted(page0),
                TerminalRunEvent.pageStarted(page1), TerminalRunEvent.pageCompleted(page1),
                TerminalRunEvent.runCompleted(json("{\"responseDisposition\":\"substantive\"}"), run + ":terminal", 1));
        Long first = tx.execute(status -> store.appendTerminalEvents(run, complete(run, valid.size()), valid, T0));
        assertThat(first).isEqualTo(2L);
        assertThat(store.findAfter(run, 1, 10)).extracting(StoredRunEvent::type).containsExactly(
                AiRunEventType.RESPONSE_PAGE_STARTED, AiRunEventType.RESPONSE_PAGE_COMPLETED,
                AiRunEventType.RESPONSE_PAGE_STARTED, AiRunEventType.RESPONSE_PAGE_COMPLETED,
                AiRunEventType.RUN_COMPLETED);
        assertThat(store.lastSeq(run)).hasValue(6);
    }

    @Test
    @DisplayName("terminal source_event_id도 128자를 넘으면 digest 키로 저장하고, 이미 쓰인 키·예약 prefix는 거부한다")
    void terminalSourceEventIds() {
        UUID run = runningRun();
        String longId = run + ":" + "t".repeat(200);
        Long first = tx.execute(status -> store.appendTerminalEvents(run, complete(run, 1),
                List.of(TerminalRunEvent.runCompleted(json("{}"), longId, 1)), T0));
        assertThat(first).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT source_event_id FROM ai_run_events WHERE run_id = ? AND seq = 2",
                String.class, run)).isEqualTo(SourceActivities.storedSourceEventId(longId)).startsWith("sha256:");

        // Activity와 같은 source_event_id를 terminal이 다시 쓰면 원인을 드러내고 rollback한다
        UUID other = runningRun();
        SourceEvent activity = started(other, "activity-1", "input_analysis", "analyzing_input");
        store.appendActivity(activity);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> store.appendTerminalEvents(other,
                complete(other, 1), List.of(TerminalRunEvent.runCompleted(json("{}"), activity.sourceEventId(), 1)), T0)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already used");
        assertThat(store.lastSeq(other)).hasValue(2);

        assertThatThrownBy(() -> TerminalRunEvent.runCompleted(json("{}"), "sha256:" + "0".repeat(64), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TerminalRunEvent.runCompleted(json("{}"), "x".repeat(257), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("digest 키 prefix로 시작하는 Activity source_event_id는 MALFORMED (키 공간 분리)")
    void reservedDigestPrefixRejected() {
        UUID run = runningRun();
        ObjectNode envelope = envelope(run.toString(), "activity.started",
                base("activity-1", "input_analysis").put("label", "analyzing_input"));
        envelope.put("source_event_id", "sha256:" + "0".repeat(64));
        assertThat(store.appendActivity(source(envelope)).outcome()).isEqualTo(Outcome.MALFORMED);
        assertThat(rows(run)).isEqualTo(1);
    }

    @Test
    @DisplayName("Activity append와 terminal 전환이 경합해도 terminal이 마지막이고 seq에 빈틈·중복이 없다")
    void activityRacingTerminal() throws Exception {
        UUID run = runningRun();
        int writers = 8;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            SourceEvent event = started(run, "activity-" + (i + 1), "code_execution", "running_calculation",
                    "Python");
            tasks.add(() -> store.appendActivity(event));
        }
        tasks.add(() -> tx.execute(status -> store.appendTerminalEvents(run, complete(run, 1),
                List.of(TerminalRunEvent.runCompleted(json("{}"))), T0)));

        List<Object> results = race(tasks.size(), tasks::get);

        long terminalSeq = (Long) results.get(writers);
        List<ActivityAppendResult> activityResults = results.subList(0, writers).stream()
                .map(ActivityAppendResult.class::cast).toList();
        assertThat(activityResults).allSatisfy(result -> assertThat(result.outcome())
                .isIn(Outcome.APPENDED, Outcome.NOT_ACCEPTING));
        assertThat(activityResults).filteredOn(ActivityAppendResult::isStored)
                .allSatisfy(result -> assertThat(result.seq()).isLessThan(terminalSeq));
        long appended = activityResults.stream().filter(ActivityAppendResult::isStored).count();
        assertThat(terminalSeq).isEqualTo(2 + appended);
        assertThat(seqs(run)).containsExactlyElementsOf(range(1, terminalSeq));
        assertThat(store.findAfter(run, terminalSeq - 1, 10)).singleElement()
                .extracting(StoredRunEvent::type).isEqualTo(AiRunEventType.RUN_COMPLETED);
    }

    // ------------------------------------------------------------------
    // 알림·조회
    // ------------------------------------------------------------------

    @Test
    @DisplayName("알림은 commit 뒤에만, payload 없이 (runId, lastSeq)로 오고 listener 예외는 append를 막지 않는다")
    void signalsAfterCommitOnly() {
        UUID run = runningRun();
        List<Long> received = new CopyOnWriteArrayList<>();
        RunEventSignals.Subscription failing = signals.subscribe(run, (id, seq) -> {
            throw new IllegalStateException("slow subscriber");
        });
        RunEventSignals.Subscription subscription = signals.subscribe(run, (id, seq) -> {
            assertThat(id).isEqualTo(run);
            received.add(seq);
        });
        try {
            tx.executeWithoutResult(status -> {
                store.appendActivity(started(run, "activity-1", "input_analysis", "analyzing_input"));
                store.appendActivity(updated(run, "activity-1", "input_analysis", 1, "읽는 중"));
                assertThat(received).isEmpty();
            });
            assertThat(received).containsExactly(2L, 3L);
            // 중복은 알림이 없다
            store.appendActivity(started(run, "activity-1", "input_analysis", "analyzing_input"));
            assertThat(received).containsExactly(2L, 3L);
        } finally {
            subscription.close();
            failing.close();
        }
        assertThat(signals.listenerCount(run)).isZero();
        store.appendActivity(started(run, "activity-2", "input_analysis", "analyzing_input"));
        assertThat(received).containsExactly(2L, 3L);
    }

    @Test
    @DisplayName("알림을 놓쳐도 cursor 이후 조회로 순서대로 catch-up하고 limit으로 나눠 읽을 수 있다")
    void catchUpFromCursor() {
        UUID run = runningRun();
        for (int i = 1; i <= 5; i++) {
            store.appendActivity(started(run, "activity-" + i, "note_retrieval", "searching_notes"));
        }
        List<Long> seen = new ArrayList<>();
        long cursor = 0;
        while (true) {
            List<StoredRunEvent> page = store.findAfter(run, cursor, 2);
            if (page.isEmpty()) {
                break;
            }
            page.forEach(event -> seen.add(event.seq()));
            cursor = page.get(page.size() - 1).seq();
        }
        assertThat(seen).containsExactlyElementsOf(range(1, 6));
        assertThat(store.findAfter(run, 6, 10)).isEmpty();
        StoredRunEvent third = store.findAfter(run, 2, 1).get(0);
        assertThat(third.cursor().toString()).isEqualTo(run + ":3");
        assertThat(third.activityId()).isEqualTo("activity-2");
        assertThat(third.toFrame().type()).isEqualTo("activity.started");
    }

    @Test
    @DisplayName("browser-run-v1 stream fixture를 store로 재현하면 replay frame이 같다(seq·type·payload·occurredAt)")
    void reproducesBrowserRunStreams() throws Exception {
        Path streams = ActivityFixtures.MIRROR.resolve("browser-run-v1/streams/valid");
        List<Path> files;
        try (Stream<Path> list = Files.list(streams)) {
            files = list.sorted().toList();
        }
        Map<UUID, Instant> replayed = new HashMap<>();
        for (Path file : files) {
            JsonNode fixture = ActivityFixtures.read(file);
            UUID run = UUID.fromString(fixture.get("runId").asText());
            // 같은 run을 다른 시점(replay)에서 본 fixture는 한 번만 저장하고 비교만 한다.
            // replay와 live의 허용 차이는 read-time sourceReferences[].available뿐이다(browser-run-v1 CATALOG).
            boolean again = replayed.containsKey(run);
            Instant terminalAt = again ? replayed.get(run)
                    : replay(run, fixture.get("frames"), fixture.get("finalStatus").asText());
            replayed.put(run, terminalAt);

            List<StoredRunEvent> stored = store.findAfter(run, 0, 100);
            JsonNode frames = fixture.get("frames");
            assertThat(stored).as(file.getFileName().toString()).hasSize(frames.size());
            for (int i = 0; i < stored.size(); i++) {
                // 실제 SSE data처럼 문자열로 직렬화한 뒤 비교한다(숫자 node 종류 차이를 없앤다)
                ObjectNode actual = (ObjectNode) objectMapper.readTree(
                        objectMapper.writeValueAsString(stored.get(i).toFrame()));
                ObjectNode expected = frames.get(i).deepCopy();
                if (isTerminalBatch(expected.get("type").asText())) {
                    // terminal transaction은 page·완료 event에 같은 commit 시각을 쓴다
                    assertThat(Instant.parse(actual.get("occurredAt").asText())).isEqualTo(terminalAt);
                    actual.remove("occurredAt");
                    expected.remove("occurredAt");
                }
                if (again) {
                    withoutAvailability(actual);
                    withoutAvailability(expected);
                }
                assertThat(actual).as(file.getFileName() + " frame " + i).isEqualTo(expected);
            }
        }
    }

    // ------------------------------------------------------------------
    // stream 재현 driver (접수 1.79·coordinator 1.83·terminal 1.82·취소 1.84가 할 순서를 흉내 낸다)
    // ------------------------------------------------------------------

    private Instant replay(UUID run, JsonNode frames, String finalStatus) {
        Map<String, Integer> revisions = new HashMap<>();
        Instant terminalAt = null;
        boolean claimed = false;
        for (int i = 0; i < frames.size(); i++) {
            JsonNode frame = frames.get(i);
            String type = frame.get("type").asText();
            Instant at = Instant.parse(frame.get("occurredAt").asText());
            long expectedSeq = frame.get("seq").asLong();
            if (type.equals("run.created")) {
                tx.executeWithoutResult(status -> {
                    insertPendingRun(run, newNote());
                    assertThat(store.appendServerEvent(run, ServerFrameType.RUN_CREATED, frame.get("payload"), at))
                            .isEqualTo(expectedSeq);
                });
            } else if (type.startsWith("activity.")) {
                if (!claimed) {
                    claim(run);
                    claimed = true;
                }
                ObjectNode payload = (ObjectNode) snake(frame.get("payload"));
                String activityId = payload.get("activity_id").asText();
                int revision = revisions.merge(activityId, 1, Integer::sum) - 1;
                ObjectNode source = envelope(run.toString(), type, payload);
                source.put("occurred_at", at.toString());
                source.put("source_event_id", run + ":" + activityId + ":r" + revision + ":"
                        + type.substring("activity.".length()));
                assertThat(store.appendActivity(source(source)))
                        .isEqualTo(new ActivityAppendResult(Outcome.APPENDED, expectedSeq));
            } else if (type.equals("run.canceling")) {
                tx.executeWithoutResult(status -> {
                    cancelRunning(run);
                    assertThat(store.appendServerEvent(run, ServerFrameType.RUN_CANCELING, frame.get("payload"), at))
                            .isEqualTo(expectedSeq);
                });
            } else {
                // page 경계부터 terminal까지 한 transaction
                List<TerminalRunEvent> batch = new ArrayList<>();
                for (; i < frames.size(); i++) {
                    batch.add(terminalEvent(frames.get(i)));
                }
                terminalAt = at;
                Instant commitAt = at;
                long first = tx.execute(status -> store.appendTerminalEvents(run,
                        terminate(run, finalStatus, frames.get(frames.size() - 1).get("payload"), batch.size()),
                        batch, commitAt));
                assertThat(first).isEqualTo(expectedSeq);
            }
        }
        return terminalAt;
    }

    private static void withoutAvailability(JsonNode node) {
        if (node.isObject()) {
            ((ObjectNode) node).remove("available");
        }
        node.forEach(RunEventStorePostgresTest::withoutAvailability);
    }

    private static TerminalRunEvent terminalEvent(JsonNode frame) {
        JsonNode payload = frame.get("payload");
        return switch (frame.get("type").asText()) {
            case "response.page.started" -> TerminalRunEvent.pageStarted(payload);
            case "response.page.completed" -> TerminalRunEvent.pageCompleted(payload);
            case "run.completed" -> TerminalRunEvent.runCompleted(payload);
            case "run.failed" -> TerminalRunEvent.runFailed(payload);
            case "run.canceled" -> TerminalRunEvent.runCanceled(payload);
            default -> throw new IllegalArgumentException(frame.get("type").asText());
        };
    }

    private static boolean isTerminalBatch(String type) {
        return type.startsWith("response.page.") || type.equals("run.completed") || type.equals("run.failed")
                || type.equals("run.canceled");
    }

    /** 1.82·1.84의 terminal 전환 UPDATE: 상태와 terminal seq를 한 문장에서 배정한다. */
    private long terminate(UUID run, String finalStatus, JsonNode terminalPayload, int events) {
        String status = jdbc.queryForObject("SELECT status FROM ai_runs WHERE run_id = ?", String.class, run);
        return switch (finalStatus) {
            case "COMPLETED" -> complete(run, events);
            case "FAILED" -> {
                String code = terminalPayload.get("code").asText();
                String failureClass = switch (code) {
                    case "verification_failed" -> "verification";
                    case "invalid_attachment" -> "attachment";
                    case "budget_exceeded" -> "budget";
                    default -> "infrastructure";
                };
                yield jdbc.queryForObject("""
                        UPDATE ai_runs SET status = 'FAILED', failure_code = ?, failure_class = ?, completed_at = now(),
                               lease_owner = NULL, lease_expires_at = NULL, last_event_seq = last_event_seq + ?
                        WHERE run_id = ? RETURNING last_event_seq
                        """, Long.class, code, failureClass, events, run);
            }
            case "CANCELED" -> "PENDING".equals(status)
                    ? jdbc.queryForObject("""
                            UPDATE ai_runs SET status = 'CANCELED', cancel_cause = ?, cancel_requested_from = 'PENDING',
                                   cancel_requested_at = now(), completed_at = now(),
                                   last_event_seq = last_event_seq + ?
                            WHERE run_id = ? AND status = 'PENDING' RETURNING last_event_seq
                            """, Long.class, terminalPayload.get("cause").asText(), events, run)
                    : jdbc.queryForObject("""
                            UPDATE ai_runs SET status = 'CANCELED', completed_at = now(), lease_owner = NULL,
                                   lease_expires_at = NULL, last_event_seq = last_event_seq + ?
                            WHERE run_id = ? AND status = 'CANCELING' RETURNING last_event_seq
                            """, Long.class, events, run);
            default -> throw new IllegalArgumentException(finalStatus);
        };
    }

    private long complete(UUID run, int events) {
        return jdbc.queryForObject("""
                UPDATE ai_runs SET status = 'COMPLETED', response_disposition = 'substantive', completed_at = now(),
                       lease_owner = NULL, lease_expires_at = NULL, last_event_seq = last_event_seq + ?
                WHERE run_id = ? AND attempt = 1 AND fence_token = 'fence-1' AND status IN ('RUNNING', 'RETRYING')
                RETURNING last_event_seq
                """, Long.class, events, run);
    }

    private void cancelRunning(UUID run) {
        jdbc.update("""
                UPDATE ai_runs SET status = 'CANCELING', cancel_cause = 'user_requested', cancel_requested_from = 'RUNNING',
                       cancel_requested_at = now()
                WHERE run_id = ? AND status = 'RUNNING'
                """, run);
    }

    // ------------------------------------------------------------------
    // 행 준비
    // ------------------------------------------------------------------

    /** run.created(seq 1)까지 저장된 RUNNING run (attempt 1, fence-1). */
    private UUID runningRun() {
        UUID run = pendingRunWithCreated();
        claim(run);
        return run;
    }

    private UUID pendingRunWithCreated() {
        return tx.execute(status -> {
            UUID run = insertPendingRun();
            store.appendServerEvent(run, ServerFrameType.RUN_CREATED, runCreated(), T0);
            return run;
        });
    }

    private void claim(UUID run) {
        jdbc.update("""
                UPDATE ai_runs SET status = 'RUNNING', attempt = 1, fence_token = 'fence-1', started_at = now(),
                       lease_owner = 'coordinator-1', lease_expires_at = now() + interval '30 seconds', heartbeat_at = now()
                WHERE run_id = ? AND status = 'PENDING'
                """, run);
    }

    private UUID insertPendingRun() {
        UUID run = UUID.randomUUID();
        insertPendingRun(run, newNote());
        return run;
    }

    /** user·assistant message 한 쌍 → {userMessageId, assistantMessageId}. */
    private UUID[] insertTurn(long noteId) {
        UUID userMessageId = UUID.randomUUID();
        UUID assistantMessageId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO chat_messages (message_id, note_id, role, status, user_input, plain_text, created_at)
                VALUES (?, ?, 'USER', 'COMPLETED', '{"text":"1번 문제 풀어줘"}', '1번 문제 풀어줘', clock_timestamp()),
                       (?, ?, 'ASSISTANT', 'PENDING', NULL, NULL, clock_timestamp() + interval '1 millisecond')
                """, userMessageId, noteId, assistantMessageId, noteId);
        return new UUID[]{userMessageId, assistantMessageId};
    }

    private static final Instant TOUCHED = Instant.parse("2026-10-17T10:00:00Z");

    /** AiRun에는 변경 메서드가 없어 reflection으로 필드 하나를 바꿔 dirty checking 전체 컬럼 UPDATE를 일으킨다. */
    private static void touch(AiRun run) {
        try {
            Field heartbeat = AiRun.class.getDeclaredField("heartbeatAt");
            heartbeat.setAccessible(true);
            heartbeat.set(run, TOUCHED);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void insertPendingRun(UUID run, long noteId) {
        UUID userMessageId = UUID.randomUUID();
        UUID assistantMessageId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO chat_messages (message_id, note_id, role, status, user_input, plain_text, created_at)
                VALUES (?, ?, 'USER', 'COMPLETED', '{"text":"1번 문제 풀어줘"}', '1번 문제 풀어줘', clock_timestamp()),
                       (?, ?, 'ASSISTANT', 'PENDING', NULL, NULL, clock_timestamp() + interval '1 millisecond')
                """, userMessageId, noteId, assistantMessageId, noteId);
        jdbc.update("""
                INSERT INTO ai_runs (run_id, request_id, user_id, note_id, user_message_id, assistant_message_id,
                                     request_fingerprint, request_snapshot, policy_snapshot, budget_policy_version,
                                     request_deadline_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, '{"input":{"text":"1번 문제 풀어줘"}}', '{"limits":{}}', 'budget-v1',
                        now() + interval '10 minutes')
                """, run, UUID.randomUUID().toString(), ALICE, noteId, userMessageId, assistantMessageId,
                "f".repeat(64));
    }

    /** Note마다 Active Run이 하나라 run마다 새 Note를 쓴다. */
    private long newNote() {
        return jdbc.queryForObject(
                "INSERT INTO notes (user_id, title, created_at, updated_at) VALUES (?, 'event note', now(), now()) "
                        + "RETURNING note_id", Long.class, ALICE);
    }

    private static Map<String, Object> runCreated() {
        return Map.of("requestId", UUID.randomUUID().toString(), "status", "PENDING");
    }

    // ------------------------------------------------------------------
    // source event 생성
    // ------------------------------------------------------------------

    private static SourceEvent source(JsonNode envelope) {
        return InternalRunWire.read(envelope.toString(), SourceEvent.class);
    }

    private static ObjectNode base(String activityId, String kind) {
        return JsonNodeFactory.instance.objectNode().put("kind", kind).put("activity_id", activityId);
    }

    private static SourceEvent event(UUID run, int attempt, String fence, String type, ObjectNode payload,
                                     String revision) {
        ObjectNode envelope = envelope(run.toString(), type, payload);
        envelope.put("attempt", attempt);
        envelope.put("fence_token", fence);
        envelope.put("source_event_id", run + ":" + payload.get("activity_id").asText() + ":" + revision + ":"
                + type.substring("activity.".length()));
        return source(envelope);
    }

    private static SourceEvent started(UUID run, String activityId, String kind, String label) {
        return event(run, 1, "fence-1", "activity.started", base(activityId, kind).put("label", label), "r0");
    }

    private static SourceEvent started(UUID run, String activityId, String kind, String label, String methodLabel) {
        return event(run, 1, "fence-1", "activity.started",
                base(activityId, kind).put("label", label).put("method_label", methodLabel), "r0");
    }

    private static SourceEvent updated(UUID run, String activityId, String kind, int revision, String message) {
        return event(run, 1, "fence-1", "activity.updated",
                base(activityId, kind).put("status", "in_progress").put("message", message), "r" + revision);
    }

    private ObjectNode json(String value) {
        try {
            return (ObjectNode) objectMapper.readTree(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    // ------------------------------------------------------------------

    private long rows(UUID run) {
        return jdbc.queryForObject("SELECT count(*) FROM ai_run_events WHERE run_id = ?", Long.class, run);
    }

    private List<Long> seqs(UUID run) {
        return jdbc.queryForList("SELECT seq FROM ai_run_events WHERE run_id = ? ORDER BY seq", Long.class, run);
    }

    private static List<Long> range(long from, long to) {
        List<Long> values = new ArrayList<>();
        for (long value = from; value <= to; value++) {
            values.add(value);
        }
        return values;
    }

    private interface IndexedTask<T> {
        Callable<T> task(int index);
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> race(int threads, IndexedTask<? extends T> factory) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<? extends T> task = factory.task(i);
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return (T) task.call();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
