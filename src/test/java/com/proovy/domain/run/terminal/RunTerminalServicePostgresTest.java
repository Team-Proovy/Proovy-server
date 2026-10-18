package com.proovy.domain.run.terminal;

import static com.proovy.domain.run.terminal.TerminalFixtures.load;
import static com.proovy.domain.run.terminal.TerminalFixtures.pair;
import static com.proovy.support.db.ProductRows.ALICE;
import static com.proovy.support.db.ProductRows.BOB_PDF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proovy.domain.run.brain.InternalRunWire;
import com.proovy.domain.run.brain.SourceEvent;
import com.proovy.domain.run.brain.StoredTerminal;
import com.proovy.domain.run.contract.ServerFrameType;
import com.proovy.domain.run.entity.AiRunEventType;
import com.proovy.domain.run.entity.AiRunStatus;
import com.proovy.domain.run.entity.RunFailureCode;
import com.proovy.domain.run.event.RunEventSignals;
import com.proovy.domain.run.event.RunEventStore;
import com.proovy.domain.run.event.StoredRunEvent;
import com.proovy.domain.run.terminal.TerminalSettlement.Outcome;
import com.proovy.domain.usage.entity.UsageCompensationReason;
import com.proovy.domain.usage.service.RequestUsageService;
import com.proovy.domain.usage.service.UsageCompensationResult;
import com.proovy.support.db.PostgresTestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import java.util.function.IntFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 실제 Postgres에서 terminal 확정(1.82)을 production 서비스로 검사한다: terminal-v2 pair별 message·solution·usage·event,
 * 중복·stale fence·CANCELING·deadline, 완료/실패/취소/deadline 경합, 실패 주입 rollback, TOCTOU 재확인, 정정 체인,
 * asset rename/삭제 뒤 snapshot 불변, commit 전 비공개와 신호 유실 뒤 복원.
 * <p>
 * fixture의 Brain asset ID(예: {@code note-alpha})는 테스트 Note의 실제 asset ID 문자열로, provenance content version은
 * 1.82 scope 철자(정수 문자열)로 바꿔 넣는다. 나머지 payload는 pin된 fixture 그대로다.
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
class RunTerminalServicePostgresTest {

    private static final String FENCE = "fence-1";
    private static final String UNDERSTANDING = "page-understanding-v1";
    private static final int CREDITS_PER_REQUEST = 10;
    private static final ObjectMapper RAW = new ObjectMapper();

    private static PostgresTestDatabase db;

    @BeforeAll
    static void createDatabase() {
        db = PostgresTestDatabase.createMigrated();
        db.runScript("db/seed/product-seed.sql");
        try (Connection c = db.connect(); Statement st = c.createStatement()) {
            st.execute("UPDATE credit_balance SET paid_credit = 1000000 WHERE user_id = " + ALICE);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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

    @Autowired RunTerminalService service;
    @Autowired TerminalReplay replay;
    @Autowired RunEventStore store;
    @Autowired RunEventSignals signals;
    @Autowired RequestUsageService usage;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    // ------------------------------------------------------------------
    // pair별 저장 결과
    // ------------------------------------------------------------------

    static Stream<String> pairs() {
        return Stream.of("check-with-correction", "clarification", "conflict-clarification", "conflict-substantive",
                "direct-verified", "hide-answer", "inconclusive-unverified", "mixed-substantive-notice", "multi-page",
                "supporting-logical-move", "unsupported-only", "zero-step-fallback", "failure",
                "failure-invalid-attachment");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pairs")
    @DisplayName("terminal-v2 pair: run·message·solution·usage·event가 한 번에 확정되고 replay가 browser payload를 복원한다")
    void pairCommitsExpectedState(String name) {
        JsonNode fixture = pair(name);
        JsonNode source = fixture.get("source");
        Run run = newRun(source, RunOptions.defaults());
        long balanceBefore = balance();

        TerminalSettlement result = service.settle(event(bind(source, run)));

        assertThat(result.outcome()).isEqualTo(Outcome.COMMITTED);
        boolean completed = source.get("type").asText().equals("run.completed");
        JsonNode payload = source.get("payload");
        UsageCompensationReason expectedReason = expectedCompensation(source);
        assertThat(result.compensation().outcome()).isEqualTo(expectedReason == null
                ? UsageCompensationResult.Outcome.NOT_COMPENSABLE : UsageCompensationResult.Outcome.COMPENSATED);
        assertThat(compensationReason(run)).isEqualTo(expectedReason);
        assertThat(balance() - balanceBefore).isEqualTo(expectedReason == null ? 0 : CREDITS_PER_REQUEST);

        Map<String, Object> row = runRow(run);
        List<StoredRunEvent> events = store.findAfter(run.runId(), 0, 100);
        // run.created(seq 1) 뒤 terminal batch만 있다
        assertThat(events.get(0).type()).isEqualTo(AiRunEventType.RUN_CREATED);
        assertThat(result.lastSeq()).isEqualTo(events.get(events.size() - 1).seq());
        assertThat(row.get("last_event_seq")).isEqualTo(result.lastSeq());
        JsonNode expectedBrowser = expectedBrowserPayload(fixture, run);
        if (completed) {
            assertThat(row.get("status")).isEqualTo("COMPLETED");
            assertThat(row.get("response_disposition")).isEqualTo(payload.get("response_disposition").asText());
            assertThat(row.get("clarification_reason")).isEqualTo(payload.path("clarification_reason").asText(null));
            assertThat(messageStatus(run)).isEqualTo("COMPLETED");
            assertThat(count("SELECT count(*) FROM verified_solutions WHERE source_run_id = ?", run.runId()))
                    .isEqualTo(payload.path("verified_solutions").size());

            int pages = payload.at("/assistant_response/response_pages").size();
            assertThat(events).hasSize(1 + pages * 2 + 1);
            for (int i = 0; i < pages; i++) {
                JsonNode browserPage = expectedBrowser.at("/assistantResponse/responsePages/" + i);
                ObjectNode started = JsonNodeFactory.instance.objectNode()
                        .put("assistantMessageId", run.assistantMessageId().toString())
                        .put("responsePageId", browserPage.get("responsePageId").asText())
                        .put("presentation", browserPage.get("presentation").asText()).put("pageIndex", i);
                if (browserPage.has("title")) {
                    started.put("title", browserPage.get("title").asText());
                }
                assertThat(events.get(1 + i * 2).type()).isEqualTo(AiRunEventType.RESPONSE_PAGE_STARTED);
                assertThat(events.get(1 + i * 2).payload()).isEqualTo(started);
                assertThat(events.get(2 + i * 2).type()).isEqualTo(AiRunEventType.RESPONSE_PAGE_COMPLETED);
            }
            StoredRunEvent terminal = events.get(events.size() - 1);
            assertThat(terminal.type()).isEqualTo(AiRunEventType.RUN_COMPLETED);
            // event에는 본문을 중복 저장하지 않는다
            assertThat(terminal.payload().has("assistantResponse")).isFalse();
            assertThat(terminal.payload().has("verificationSummary")).isFalse();
            ObjectNode hydrated = TerminalReplay.completedPayload(terminal.payload(),
                    replay.findCompleted(run.runId()).orElseThrow(), assetId -> true);
            assertThat(normalize(hydrated)).isEqualTo(normalize(expectedBrowser));
        } else {
            assertThat(row.get("status")).isEqualTo("FAILED");
            assertThat(row.get("failure_code")).isEqualTo(payload.get("code").asText());
            assertThat(row.get("failure_class")).isEqualTo(payload.get("failure_class").asText());
            assertThat(messageStatus(run)).isEqualTo("FAILED");
            assertThat(replay.findCompleted(run.runId())).isEmpty();
            assertThat(count("SELECT count(*) FROM verified_solutions WHERE source_run_id = ?", run.runId())).isZero();
            assertThat(events).hasSize(2);
            assertThat(events.get(1).type()).isEqualTo(AiRunEventType.RUN_FAILED);
            assertThat(normalize(events.get(1).payload())).isEqualTo(normalize(expectedBrowser));
        }
        // terminal은 lease·grant를 풀고, source_event_id·attempt를 남긴다
        assertThat(row.get("lease_owner")).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT source_event_id FROM ai_run_events WHERE run_id = ? AND event_type IN ('run.completed', 'run.failed')
                """, String.class, run.runId())).isEqualTo(source.get("source_event_id").asText());
    }

    @Test
    @DisplayName("multi-page: 저장 event가 browser-run-v1 stream(substantive-completed)의 page 경계·run.completed와 같다")
    void multiPageMatchesBrowserStream() {
        JsonNode stream = load("browser-run-v1/streams/valid/substantive-completed.json");
        Run run = newRun(pair("multi-page").get("source"), RunOptions.defaults());
        service.settle(event(bind(pair("multi-page").get("source"), run)));

        List<StoredRunEvent> events = store.findAfter(run.runId(), 0, 100);
        List<JsonNode> expected = new ArrayList<>();
        stream.get("frames").forEach(frame -> {
            String type = frame.get("type").asText();
            if (type.startsWith("response.page.") || type.equals("run.completed")) {
                expected.add(withMessageId(frame.get("payload"), run.assistantMessageId()));
            }
        });
        List<StoredRunEvent> terminalBatch = events.subList(1, events.size());
        assertThat(terminalBatch).hasSize(expected.size());
        for (int i = 0; i < expected.size() - 1; i++) {
            assertThat(normalize(terminalBatch.get(i).payload())).isEqualTo(normalize(expected.get(i)));
        }
        StoredRunEvent completed = terminalBatch.get(terminalBatch.size() - 1);
        assertThat(normalize(TerminalReplay.completedPayload(completed.payload(),
                replay.findCompleted(run.runId()).orElseThrow(), id -> true)))
                .isEqualTo(normalize(expected.get(expected.size() - 1)));
        // terminal batch는 같은 commit 시각 하나를 쓴다
        assertThat(terminalBatch.stream().map(StoredRunEvent::occurredAt).distinct()).hasSize(1);
    }

    @Test
    @DisplayName("기존 solution 재표현(explain-existing): 같은 Note 이전 run의 solution 참조로 수락되고 새 solution을 만들지 않는다")
    void explainExistingReusesNoteSolution() {
        Run first = newRun(pair("direct-verified").get("source"), RunOptions.defaults());
        assertThat(service.settle(event(bind(pair("direct-verified").get("source"), first))).outcome())
                .isEqualTo(Outcome.COMMITTED);

        for (String name : List.of("explain-existing", "explain-existing-hide-answer")) {
            JsonNode source = pair(name).get("source");
            Run run = newRun(source, RunOptions.defaults().inNote(first.noteId())
                    .referencing("solution-1", first.assistantMessageId()));
            TerminalSettlement result = service.settle(event(bind(source, run)));
            assertThat(result.outcome()).as(name).isEqualTo(Outcome.COMMITTED);
            assertThat(result.compensation().outcome()).isEqualTo(UsageCompensationResult.Outcome.NOT_COMPENSABLE);
            assertThat(count("SELECT count(*) FROM verified_solutions WHERE source_run_id = ?", run.runId())).isZero();
            assertThat(normalize(TerminalReplay.completedPayload(lastEvent(run).payload(),
                    replay.findCompleted(run.runId()).orElseThrow(), id -> true)))
                    .isEqualTo(normalize(expectedBrowserPayload(pair(name), run)));
        }
    }

    // ------------------------------------------------------------------
    // 중복·stale·상태
    // ------------------------------------------------------------------

    @Test
    @DisplayName("같은 terminal 재전달은 DUPLICATE, 같은 source_event_id의 다른 결과는 CONFLICTING_DUPLICATE — 둘 다 아무것도 바꾸지 않는다")
    void redeliveryAndChangedOutcome() {
        JsonNode redelivery = load("terminal-v2/redelivery.json");
        JsonNode first = redelivery.at("/same_semantic/first");
        Run run = newRun(first, RunOptions.defaults());
        TerminalSettlement committed = service.settle(event(bind(first, run)));
        assertThat(committed.outcome()).isEqualTo(Outcome.COMMITTED);
        Snapshot before = snapshot(run);

        TerminalSettlement again = service.settle(event(bind(redelivery.at("/same_semantic/retry"), run)));
        assertThat(again.outcome()).isEqualTo(Outcome.DUPLICATE);
        assertThat(again.lastSeq()).isEqualTo(committed.lastSeq());
        assertThat(again.isSettled()).isTrue();

        TerminalSettlement changed = service.settle(event(bind(redelivery.at("/changed_outcome/retry"), run)));
        assertThat(changed.outcome()).isEqualTo(Outcome.CONFLICTING_DUPLICATE);
        assertThat(changed.runStatus()).isEqualTo(AiRunStatus.COMPLETED);

        // 저장 terminal(status 조회 회수)로 와도 같은 판정이다
        StoredTerminal stored = InternalRunWire.read(bind(first, run), StoredTerminal.class);
        assertThat(service.settle(stored).outcome()).isEqualTo(Outcome.DUPLICATE);
        assertThat(snapshot(run)).isEqualTo(before);
    }

    @Test
    @DisplayName("동시에 같은 terminal 6번: 하나만 COMMITTED, 나머지는 DUPLICATE이고 event·solution·차감은 한 벌")
    void concurrentRedeliveryCommitsOnce() throws Exception {
        JsonNode source = pair("direct-verified").get("source");
        Run run = newRun(source, RunOptions.defaults());
        SourceEvent event = event(bind(source, run));

        List<TerminalSettlement> results = race(6, i -> () -> service.settle(event));

        assertThat(results).filteredOn(r -> r.outcome() == Outcome.COMMITTED).hasSize(1);
        assertThat(results).filteredOn(r -> r.outcome() == Outcome.DUPLICATE).hasSize(5);
        assertThat(store.findAfter(run.runId(), 0, 100)).hasSize(1 + 3);
        assertThat(count("SELECT count(*) FROM verified_solutions WHERE source_run_id = ?", run.runId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM request_usage_charges WHERE run_id = ?", run.runId())).isEqualTo(1);
    }

    @Test
    @DisplayName("이전 attempt의 늦은 terminal은 STALE_FENCE로 버려지고 현재 attempt 결과만 확정된다")
    void staleFenceIsDiscarded() {
        JsonNode source = pair("direct-verified").get("source");
        Run run = newRun(source, RunOptions.defaults());
        jdbc.update("UPDATE ai_runs SET attempt = 2, fence_token = 'fence-2' WHERE run_id = ?", run.runId());
        Snapshot before = snapshot(run);

        TerminalSettlement stale = service.settle(event(bind(source, run)));
        assertThat(stale.outcome()).isEqualTo(Outcome.STALE_FENCE);
        assertThat(stale.isObsolete()).isTrue();
        assertThat(snapshot(run)).isEqualTo(before);

        ObjectNode current = bind(source, run);
        current.put("attempt", 2).put("fence_token", "fence-2");
        assertThat(service.settle(event(current)).outcome()).isEqualTo(Outcome.COMMITTED);
        assertThat(jdbc.queryForObject("SELECT attempt FROM ai_run_events WHERE run_id = ? AND event_type = 'run.completed'",
                Integer.class, run.runId())).isEqualTo(2);
    }

    @Test
    @DisplayName("CANCELING·PENDING run은 terminal을 받지 않고 message·usage를 바꾸지 않는다")
    void cancelingAndPendingDoNotAccept() {
        JsonNode source = pair("clarification").get("source");
        Run canceling = newRun(source, RunOptions.defaults());
        cancel(canceling.runId());
        Snapshot before = snapshot(canceling);
        TerminalSettlement result = service.settle(event(bind(source, canceling)));
        assertThat(result.outcome()).isEqualTo(Outcome.NOT_ACCEPTING);
        assertThat(result.runStatus()).isEqualTo(AiRunStatus.CANCELING);
        assertThat(snapshot(canceling)).isEqualTo(before);
        assertThat(service.failByServer(canceling.runId(), 1, FENCE, RunFailureCode.INTERNAL_ERROR, true).outcome())
                .isEqualTo(Outcome.NOT_ACCEPTING);
        assertThat(service.expireDeadline(canceling.runId()).outcome()).isEqualTo(Outcome.NOT_ACCEPTING);

        Run pending = newRun(source, RunOptions.defaults().unclaimed());
        assertThat(service.settle(event(bind(source, pending))).outcome()).isEqualTo(Outcome.STALE_FENCE);
        assertThat(messageStatus(pending)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("run_id가 UUID가 아니거나 run이 없으면 UNKNOWN_RUN, 저장 run.canceled는 취소 경로라 REJECTED")
    void unknownRunAndCanceledStoredTerminal() {
        ObjectNode source = (ObjectNode) pair("failure").get("source").deepCopy();
        assertThat(service.settle(event(source)).outcome()).isEqualTo(Outcome.UNKNOWN_RUN);
        source.put("run_id", UUID.randomUUID().toString());
        assertThat(service.settle(event(source)).outcome()).isEqualTo(Outcome.UNKNOWN_RUN);

        ObjectNode canceled = (ObjectNode) source.deepCopy();
        canceled.put("type", "run.canceled");
        canceled.set("payload", JsonNodeFactory.instance.objectNode().put("cause", "user_requested"));
        TerminalSettlement result = service.settle(InternalRunWire.read(canceled, StoredTerminal.class));
        assertThat(result.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(result.rejection()).isEqualTo(TerminalRejectionReason.NOT_PUBLICATION_TERMINAL);
    }

    // ------------------------------------------------------------------
    // deadline·Server 실패
    // ------------------------------------------------------------------

    @Test
    @DisplayName("deadline이 지난 run의 terminal은 DEADLINE_PASSED, expireDeadline이 timeout 실패·보상을 한 번 확정한다")
    void deadlineExpiry() {
        JsonNode source = pair("direct-verified").get("source");
        Run run = newRun(source, RunOptions.defaults().deadline(Instant.now().minusSeconds(1)));

        assertThat(service.settle(event(bind(source, run))).outcome()).isEqualTo(Outcome.DEADLINE_PASSED);
        assertThat(messageStatus(run)).isEqualTo("PENDING");

        TerminalSettlement expired = service.expireDeadline(run.runId());
        assertThat(expired.outcome()).isEqualTo(Outcome.COMMITTED);
        assertThat(expired.compensation().outcome()).isEqualTo(UsageCompensationResult.Outcome.COMPENSATED);
        assertThat(runRow(run).get("failure_code")).isEqualTo("timeout");
        assertThat(compensationReason(run)).isEqualTo(UsageCompensationReason.INFRASTRUCTURE_FAILURE);
        JsonNode failed = lastEvent(run).payload();
        assertThat(failed.get("code").asText()).isEqualTo("timeout");
        assertThat(failed.has("reason")).isFalse();

        assertThat(service.expireDeadline(run.runId()).outcome()).isEqualTo(Outcome.NOT_ACCEPTING);
        // 이미 확정된 run에 늦은 Brain terminal: 다른 결과라 NOT_ACCEPTING (source_event_id도 다르다)
        assertThat(service.settle(event(bind(source, run))).outcome()).isEqualTo(Outcome.NOT_ACCEPTING);

        Run future = newRun(source, RunOptions.defaults());
        assertThat(service.expireDeadline(future.runId()).outcome()).isEqualTo(Outcome.DEADLINE_NOT_REACHED);
    }

    @Test
    @DisplayName("failByServer: PENDING(fence 없음)·RUNNING을 고정 문구 실패로 끝내고 보상한다. 다른 fence는 STALE_FENCE")
    void serverFailure() {
        JsonNode source = pair("failure").get("source");
        Run pending = newRun(source, RunOptions.defaults().unclaimed());
        assertThat(service.failByServer(pending.runId(), 1, FENCE, RunFailureCode.INTERNAL_ERROR, true).outcome())
                .isEqualTo(Outcome.STALE_FENCE);
        TerminalSettlement failed = service.failByServer(pending.runId(), 0, null, RunFailureCode.INTERNAL_ERROR, true);
        assertThat(failed.outcome()).isEqualTo(Outcome.COMMITTED);
        assertThat(compensationReason(pending)).isEqualTo(UsageCompensationReason.INFRASTRUCTURE_FAILURE);
        assertThat(lastEvent(pending).payload()).isEqualTo(JsonNodeFactory.instance.objectNode()
                .put("code", "internal_error").put("message", "응답 처리를 완료하지 못했습니다.")
                .put("recoverable", true).put("assistantMessageId", pending.assistantMessageId().toString()));
        assertThat(jdbc.queryForObject("SELECT source_event_id FROM ai_run_events WHERE run_id = ? AND event_type = 'run.failed'",
                String.class, pending.runId())).isNull();
        assertThat(runRow(pending).get("terminal_source_sha256")).isNull();

        Run running = newRun(source, RunOptions.defaults());
        TerminalSettlement budget = service.failByServer(running.runId(), 1, FENCE, RunFailureCode.BUDGET_EXCEEDED,
                false);
        assertThat(budget.outcome()).isEqualTo(Outcome.COMMITTED);
        assertThat(compensationReason(running)).isEqualTo(UsageCompensationReason.BUDGET_EXCEEDED);
        assertThatThrownBy(() -> service.failByServer(running.runId(), 1, FENCE, RunFailureCode.INVALID_ATTACHMENT,
                false)).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // 경합
    // ------------------------------------------------------------------

    @Test
    @DisplayName("완료와 Server 실패가 동시에 와도 하나만 확정되고, 답변 확정과 실패 보상이 함께 생기지 않는다")
    void completionVersusFailureRace() throws Exception {
        JsonNode source = pair("direct-verified").get("source");
        for (int round = 0; round < 8; round++) {
            Run run = newRun(source, RunOptions.defaults());
            SourceEvent event = event(bind(source, run));
            List<TerminalSettlement> results = race(2, i -> () -> i == 0 ? service.settle(event)
                    : service.failByServer(run.runId(), 1, FENCE, RunFailureCode.PROVIDER_ERROR, true));
            assertThat(results).filteredOn(r -> r.outcome() == Outcome.COMMITTED).hasSize(1);
            assertExactlyOneOutcome(run);
        }
    }

    @Test
    @DisplayName("완료와 CANCELING 전환이 경합하면 하나만 이긴다: 취소가 먼저면 늦은 결과는 run·message를 바꾸지 않는다")
    void completionVersusCancelRace() throws Exception {
        JsonNode source = pair("direct-verified").get("source");
        int completedWins = 0;
        for (int round = 0; round < 8; round++) {
            Run run = newRun(source, RunOptions.defaults());
            SourceEvent event = event(bind(source, run));
            List<Object> results = race(2, i -> () -> i == 0 ? service.settle(event) : (Object) cancel(run.runId()));
            TerminalSettlement settled = (TerminalSettlement) results.get(0);
            int canceled = (Integer) results.get(1);
            assertThat(settled.outcome() == Outcome.COMMITTED).isNotEqualTo(canceled == 1);
            String status = (String) runRow(run).get("status");
            if (settled.outcome() == Outcome.COMMITTED) {
                completedWins++;
                assertThat(status).isEqualTo("COMPLETED");
            } else {
                assertThat(settled.outcome()).isEqualTo(Outcome.NOT_ACCEPTING);
                assertThat(status).isEqualTo("CANCELING");
                assertThat(messageStatus(run)).isEqualTo("PENDING");
                assertThat(count("SELECT count(*) FROM verified_solutions WHERE source_run_id = ?", run.runId()))
                        .isZero();
            }
            assertThat(compensationReason(run)).isNull();
        }
        assertThat(completedWins).isBetween(0, 8);
    }

    @Test
    @DisplayName("deadline 경계에서 완료와 만료가 경합해도 하나만 확정된다 (완료면 무보상, 만료면 보상)")
    void completionVersusDeadlineRace() throws Exception {
        JsonNode source = pair("direct-verified").get("source");
        for (int round = 0; round < 6; round++) {
            long delay = 300 + (round % 3) * 60L;
            Instant deadline = Instant.now().plusMillis(400);
            Run run = newRun(source, RunOptions.defaults().deadline(deadline));
            SourceEvent event = event(bind(source, run));
            List<TerminalSettlement> results = race(2, i -> () -> {
                if (i == 0) {
                    Thread.sleep(delay);
                    return service.settle(event);
                }
                while (true) {
                    TerminalSettlement expired = service.expireDeadline(run.runId());
                    if (expired.outcome() != Outcome.DEADLINE_NOT_REACHED) {
                        return expired;
                    }
                    Thread.sleep(20);
                }
            });
            assertThat(results).filteredOn(r -> r.outcome() == Outcome.COMMITTED).hasSize(1);
            // 완료가 deadline 전에 commit했거나, deadline 뒤라 버려졌거나(DEADLINE_PASSED), 만료 실패가 먼저 commit됐다(NOT_ACCEPTING)
            assertThat(results.get(0).outcome()).isIn(Outcome.COMMITTED, Outcome.DEADLINE_PASSED,
                    Outcome.NOT_ACCEPTING);
            assertExactlyOneOutcome(run);
        }
    }

    // ------------------------------------------------------------------
    // 실패 주입 (rollback)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("보상·event·solution 저장 중 실패하면 run·message·solution·event·차감이 모두 rollback되고 신호가 없다")
    void failureInjectionRollsBackEverything() {
        record Injection(String pair, String table, String condition) {
        }
        List<Injection> injections = List.of(
                new Injection("clarification", "request_usage_compensations", "TRUE"),
                new Injection("direct-verified", "ai_run_events", "NEW.event_type = 'run.completed'"),
                new Injection("direct-verified", "verified_solutions", "TRUE"),
                new Injection("failure", "request_usage_compensations", "TRUE"));
        for (Injection injection : injections) {
            JsonNode source = pair(injection.pair()).get("source");
            Run run = newRun(source, RunOptions.defaults());
            Snapshot before = snapshot(run);
            long balanceBefore = balance();
            List<Long> signalled = new CopyOnWriteArrayList<>();
            jdbc.execute("""
                    CREATE FUNCTION t182_inject() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN IF %s THEN RAISE EXCEPTION 'injected failure'; END IF; RETURN NEW; END; $$
                    """.formatted(injection.condition()));
            jdbc.execute("CREATE TRIGGER t182_inject BEFORE INSERT ON " + injection.table()
                    + " FOR EACH ROW EXECUTE FUNCTION t182_inject()");
            try (RunEventSignals.Subscription ignored = signals.subscribe(run.runId(), (id, seq) -> signalled.add(seq))) {
                assertThatThrownBy(() -> service.settle(event(bind(source, run))))
                        .as(injection.toString()).hasStackTraceContaining("injected failure");
                assertThat(snapshot(run)).as(injection.toString()).isEqualTo(before);
                assertThat(balance()).isEqualTo(balanceBefore);
                assertThat(signalled).isEmpty();
            } finally {
                jdbc.execute("DROP TRIGGER t182_inject ON " + injection.table());
                jdbc.execute("DROP FUNCTION t182_inject()");
            }
            // 원인이 사라지면 같은 terminal 재전달이 정상 확정된다(rollback이 흔적을 남기지 않았다)
            assertThat(service.settle(event(bind(source, run))).outcome()).isEqualTo(Outcome.COMMITTED);
        }
    }

    // ------------------------------------------------------------------
    // TOCTOU·다른 Note·데이터 미준비
    // ------------------------------------------------------------------

    @Test
    @DisplayName("판정 직전 asset이 DELETING·version 변경되면 거부, 다른 Note asset 인용도 거부 — run은 그대로")
    void sourceScopeIsCheckedAgainstLockedRows() throws Exception {
        JsonNode source = pair("direct-verified").get("source");

        Run deleting = newRun(source, RunOptions.defaults());
        long asset = deleting.assets().values().iterator().next();
        // 삭제 transaction이 asset을 잡고 있는 동안 terminal이 오면 기다렸다가 DELETING을 본다
        try (Connection deleter = db.connect()) {
            deleter.setAutoCommit(false);
            try (Statement st = deleter.createStatement()) {
                st.executeUpdate("UPDATE assets SET status = 'DELETING', deletion_operation_id = gen_random_uuid(), "
                        + "deletion_requested_at = now() WHERE id = " + asset);
            }
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<TerminalSettlement> pendingSettle = pool.submit(() -> service.settle(event(bind(source, deleting))));
                Thread.sleep(300);
                assertThat(pendingSettle.isDone()).as("terminal waits for the asset lock").isFalse();
                deleter.commit();
                TerminalSettlement result = pendingSettle.get(30, TimeUnit.SECONDS);
                assertThat(result.outcome()).isEqualTo(Outcome.REJECTED);
                assertThat(result.rejection()).isEqualTo(TerminalRejectionReason.SOURCE_SCOPE);
            } finally {
                pool.shutdownNow();
            }
        }
        assertThat(runRow(deleting).get("status")).isEqualTo("RUNNING");
        assertThat(messageStatus(deleting)).isEqualTo("PENDING");

        Run changed = newRun(source, RunOptions.defaults());
        jdbc.update("UPDATE assets SET content_version = 2 WHERE id = ?", changed.assets().values().iterator().next());
        assertThat(service.settle(event(bind(source, changed))).rejection())
                .isEqualTo(TerminalRejectionReason.SOURCE_SCOPE);

        // 다른 사용자·Note의 asset ID를 인용
        Run foreign = newRun(source, RunOptions.defaults());
        ObjectNode foreignSource = bind(source, foreign);
        String foreignId = String.valueOf(BOB_PDF);
        foreignSource.at("/payload/assistant_response/source_references").forEach(ref ->
                ((ObjectNode) ref).put("asset_id", foreignId));
        foreignSource.at("/payload/selected_page_provenance").forEach(item ->
                ((ObjectNode) item).put("asset_id", foreignId));
        TerminalSettlement result = service.settle(event(foreignSource));
        assertThat(result.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(result.rejection()).isEqualTo(TerminalRejectionReason.SOURCE_SCOPE);
        assertThat(result.isSettled()).isFalse();
    }

    @Test
    @DisplayName("다른 Note의 solution을 가리키는 참조는 scope에 들어가지 않아 재표현이 거부된다")
    void otherNoteSolutionReferenceRejected() {
        Run other = newRun(pair("direct-verified").get("source"), RunOptions.defaults());
        service.settle(event(bind(pair("direct-verified").get("source"), other)));

        JsonNode source = pair("explain-existing").get("source");
        Run run = newRun(source, RunOptions.defaults().referencing("solution-1", other.assistantMessageId()));
        TerminalSettlement result = service.settle(event(bind(source, run)));
        assertThat(result.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(result.rejection()).isEqualTo(TerminalRejectionReason.SOLUTION_REFERENCE_SCOPE);
        assertThat(runRow(run).get("status")).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("page_count 없는 PDF dependency는 SCOPE_NOT_READY(데이터 미준비)로 분류하고 run을 그대로 둔다")
    void pdfWithoutPageCountIsScopeNotReady() {
        JsonNode source = pair("direct-verified").get("source");
        Run run = newRun(source, RunOptions.defaults().withoutPageCount());
        TerminalSettlement result = service.settle(event(bind(source, run)));
        assertThat(result.outcome()).isEqualTo(Outcome.SCOPE_NOT_READY);
        assertThat(runRow(run).get("status")).isEqualTo("RUNNING");
    }

    // ------------------------------------------------------------------
    // 정정·source snapshot 불변
    // ------------------------------------------------------------------

    @Test
    @DisplayName("정정은 새 row를 만들고 이전 row content·hash는 그대로, superseded 대상의 재정정은 거부된다")
    void correctionCreatesNewRowAndKeepsOldContent() {
        // 첫 run: solution-3 (direct-verified의 solution-1을 solution-3으로 바꿔 Brain ID가 겹치지 않게 한다)
        JsonNode original = renamed(pair("direct-verified").get("source"), "solution-1", "solution-3");
        Run first = newRun(original, RunOptions.defaults());
        assertThat(service.settle(event(bind(original, first))).outcome()).isEqualTo(Outcome.COMMITTED);
        UUID target = jdbc.queryForObject("SELECT solution_id FROM verified_solutions WHERE source_run_id = ?",
                UUID.class, first.runId());
        String contentBefore = solutionContent(target);

        ObjectNode revising = (ObjectNode) pair("direct-verified").get("source").deepCopy();
        ((ObjectNode) revising.at("/payload/verified_solutions/0")).put("revises_solution_id", "solution-3");
        Run second = newRun(revising, RunOptions.defaults().inNote(first.noteId())
                .referencing("solution-3", first.assistantMessageId()));
        assertThat(service.settle(event(bind(revising, second))).outcome()).isEqualTo(Outcome.COMMITTED);

        UUID successor = jdbc.queryForObject("SELECT solution_id FROM verified_solutions WHERE source_run_id = ?",
                UUID.class, second.runId());
        assertThat(jdbc.queryForObject("SELECT revises_solution_id FROM verified_solutions WHERE solution_id = ?",
                UUID.class, successor)).isEqualTo(target);
        Map<String, Object> old = jdbc.queryForMap(
                "SELECT status, superseded_by_solution_id FROM verified_solutions WHERE solution_id = ?", target);
        assertThat(old.get("status")).isEqualTo("SUPERSEDED");
        assertThat(old.get("superseded_by_solution_id")).isEqualTo(successor);
        assertThat(solutionContent(target)).isEqualTo(contentBefore);

        // 이미 superseded된 solution-3을 다시 정정하려는 run은 REVISION으로 거부된다
        Run third = newRun(revising, RunOptions.defaults().inNote(first.noteId())
                .referencing("solution-3", first.assistantMessageId()));
        TerminalSettlement rejected = service.settle(event(bind(revising, third)));
        assertThat(rejected.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(rejected.rejection()).isEqualTo(TerminalRejectionReason.REVISION);
        assertThat(count("SELECT count(*) FROM verified_solutions WHERE note_id = ?", first.noteId())).isEqualTo(2);
    }

    @Test
    @DisplayName("asset rename·DELETING 뒤에도 message JSON·plain text·content hash가 같고 availability만 read-time으로 바뀐다")
    void sourceSnapshotSurvivesAssetRenameAndDelete() {
        JsonNode source = pair("direct-verified").get("source");
        Run run = newRun(source, RunOptions.defaults());
        service.settle(event(bind(source, run)));
        long asset = run.assets().values().iterator().next();
        Map<String, Object> before = message(run);

        jdbc.update("UPDATE assets SET file_name = 'renamed.pdf', status = 'DELETING', "
                + "deletion_operation_id = gen_random_uuid(), deletion_requested_at = now() WHERE id = ?", asset);

        assertThat(message(run)).isEqualTo(before);
        TerminalReplay.CompletedMessage stored = replay.findCompleted(run.runId()).orElseThrow();
        ObjectNode hydrated = TerminalReplay.completedPayload(lastEvent(run).payload(), stored, id -> false);
        JsonNode reference = hydrated.at("/assistantResponse/sourceReferences/0");
        assertThat(reference.get("displayLabelSnapshot").asText()).isEqualTo("개념 노트 3–4쪽");
        assertThat(reference.get("assetId").asText()).isEqualTo(String.valueOf(asset));
        assertThat(reference.get("available").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------------
    // 공개 경계
    // ------------------------------------------------------------------

    @Test
    @DisplayName("commit 전에는 다른 연결에 본문·event가 보이지 않고 신호도 없다. commit 뒤 신호가 유실돼도 DB에서 복원된다")
    void nothingIsVisibleBeforeCommitAndReplayRecoversAfter() throws Exception {
        JsonNode fixture = pair("multi-page");
        Run run = newRun(fixture.get("source"), RunOptions.defaults());
        SourceEvent event = event(bind(fixture.get("source"), run));
        List<Long> signalled = new CopyOnWriteArrayList<>();
        try (RunEventSignals.Subscription ignored = signals.subscribe(run.runId(), (id, seq) -> signalled.add(seq));
             Connection observer = db.connect()) {
            TerminalSettlement result = tx.execute(status -> {
                TerminalSettlement inside = service.settle(event);
                assertThat(inside.outcome()).isEqualTo(Outcome.COMMITTED);
                // 같은 transaction 안: 아직 commit 전
                assertThat(observe(observer, "SELECT status FROM chat_messages WHERE message_id = '"
                        + run.assistantMessageId() + "'")).isEqualTo("PENDING");
                assertThat(observe(observer, "SELECT response_pages::text FROM chat_messages WHERE message_id = '"
                        + run.assistantMessageId() + "'")).isNull();
                assertThat(observe(observer, "SELECT count(*)::text FROM ai_run_events WHERE run_id = '"
                        + run.runId() + "'")).isEqualTo("1");
                assertThat(observe(observer, "SELECT count(*)::text FROM verified_solutions WHERE source_run_id = '"
                        + run.runId() + "'")).isEqualTo("0");
                assertThat(signalled).isEmpty();
                return inside;
            });
            assertThat(signalled).containsExactly(result.lastSeq());
            assertThat(observe(observer, "SELECT status FROM chat_messages WHERE message_id = '"
                    + run.assistantMessageId() + "'")).isEqualTo("COMPLETED");
        }

        // 신호를 받은 구독자가 없어도(유실) cursor 이후 catch-up과 canonical message로 같은 frame이 나온다
        List<StoredRunEvent> replayed = store.findAfter(run.runId(), 1, 100);
        assertThat(replayed).extracting(StoredRunEvent::type).containsExactly(
                AiRunEventType.RESPONSE_PAGE_STARTED, AiRunEventType.RESPONSE_PAGE_COMPLETED,
                AiRunEventType.RESPONSE_PAGE_STARTED, AiRunEventType.RESPONSE_PAGE_COMPLETED,
                AiRunEventType.RUN_COMPLETED);
        ObjectNode payload = TerminalReplay.completedPayload(replayed.get(replayed.size() - 1).payload(),
                replay.findCompleted(run.runId()).orElseThrow(), id -> true);
        assertThat(normalize(payload)).isEqualTo(normalize(expectedBrowserPayload(fixture, run)));
        assertThat(store.lastSeq(run.runId())).hasValue(replayed.get(replayed.size() - 1).seq());
    }

    // ------------------------------------------------------------------
    // 도우미
    // ------------------------------------------------------------------

    record Run(UUID runId, String requestId, long noteId, UUID assistantMessageId, Map<String, Long> assets) {
    }

    record RunOptions(Long noteId, Instant deadline, boolean claimed, boolean pageCount,
                      List<String[]> solutionRefs) {

        static RunOptions defaults() {
            return new RunOptions(null, null, true, true, List.of());
        }

        RunOptions inNote(long note) {
            return new RunOptions(note, deadline, claimed, pageCount, solutionRefs);
        }

        RunOptions deadline(Instant at) {
            return new RunOptions(noteId, at, claimed, pageCount, solutionRefs);
        }

        RunOptions unclaimed() {
            return new RunOptions(noteId, deadline, false, pageCount, solutionRefs);
        }

        RunOptions withoutPageCount() {
            return new RunOptions(noteId, deadline, claimed, false, solutionRefs);
        }

        RunOptions referencing(String publicId, UUID sourceMessageId) {
            List<String[]> refs = new ArrayList<>(solutionRefs);
            refs.add(new String[] {publicId, sourceMessageId.toString()});
            return new RunOptions(noteId, deadline, claimed, pageCount, refs);
        }
    }

    /**
     * 접수(1.79)·claim(1.83)이 남기는 모양을 직접 만든다: Note·asset·user/assistant message·PENDING run·dependency·
     * 최초 차감·run.created(seq 1), 그리고 RUNNING attempt 1 / fence-1.
     */
    private Run newRun(JsonNode source, RunOptions options) {
        return tx.execute(status -> {
            long noteId = options.noteId() != null ? options.noteId() : jdbc.queryForObject("""
                    INSERT INTO notes (user_id, title, created_at, updated_at) VALUES (?, 'terminal', now(), now())
                    RETURNING note_id
                    """, Long.class, ALICE);
            Map<String, Long> assets = new LinkedHashMap<>();
            for (String brainId : brainAssetIds(source)) {
                assets.put(brainId, jdbc.queryForObject("""
                        INSERT INTO assets (user_id, note_id, file_name, file_size, mime_type, object_key, source, status,
                                            content_version, sha256, page_count, created_at, updated_at)
                        VALUES (?, ?, 'concept.pdf', 1024, 'application/pdf', 'k', 'upload', 'ACTIVE', 1, ?, ?, now(), now())
                        RETURNING id
                        """, Long.class, ALICE, noteId, "a".repeat(64), options.pageCount() ? 20 : null));
            }
            UUID userMessage = UUID.randomUUID();
            UUID assistantMessage = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO chat_messages (message_id, note_id, role, status, user_input, plain_text, created_at)
                    VALUES (?, ?, 'USER', 'COMPLETED', '{"text":"풀어줘"}', '풀어줘', now()),
                           (?, ?, 'ASSISTANT', 'PENDING', NULL, NULL, now() + interval '1 microsecond')
                    """, userMessage, noteId, assistantMessage, noteId);
            ObjectNode request = JsonNodeFactory.instance.objectNode();
            request.putObject("input").put("text", "풀어줘");
            ArrayNode refs = request.putArray("verified_solution_refs");
            for (String[] ref : options.solutionRefs()) {
                refs.addObject().put("solution_id", ref[0]).put("source_message_id", ref[1]);
            }
            ObjectNode policy = JsonNodeFactory.instance.objectNode();
            policy.putObject("asset_versions").put("page_understanding_version", UNDERSTANDING);
            UUID runId = UUID.randomUUID();
            String requestId = UUID.randomUUID().toString();
            Instant deadline = options.deadline() != null ? options.deadline() : Instant.now().plusSeconds(600);
            jdbc.update("""
                    INSERT INTO ai_runs (run_id, request_id, user_id, note_id, user_message_id, assistant_message_id,
                                         request_fingerprint, request_snapshot, policy_snapshot, budget_policy_version,
                                         request_deadline_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), 'budget-v1', ?, now())
                    """, runId, requestId, ALICE, noteId, userMessage, assistantMessage, "f".repeat(64),
                    request.toString(), policy.toString(),
                    OffsetDateTime.ofInstant(deadline.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC));
            for (long asset : assets.values()) {
                jdbc.update("""
                        INSERT INTO run_asset_dependencies (run_id, note_id, asset_id, dependency_kind, content_version)
                        VALUES (?, ?, ?, 'CORPUS_SNAPSHOT', 1)
                        """, runId, noteId, asset);
            }
            usage.chargeRequest(ALICE, requestId, runId);
            store.appendServerEvent(runId, ServerFrameType.RUN_CREATED,
                    JsonNodeFactory.instance.objectNode().put("status", "PENDING"), Instant.now());
            if (options.claimed()) {
                jdbc.update("""
                        UPDATE ai_runs SET status = 'RUNNING', attempt = 1, fence_token = ?, started_at = now(),
                               lease_owner = 'coordinator-1', lease_expires_at = now() + interval '30 seconds',
                               heartbeat_at = now()
                        WHERE run_id = ?
                        """, FENCE, runId);
            }
            return new Run(runId, requestId, noteId, assistantMessage, assets);
        });
    }

    private static List<String> brainAssetIds(JsonNode source) {
        List<String> ids = new ArrayList<>();
        source.at("/payload/selected_page_provenance").forEach(item -> {
            if (!ids.contains(item.get("asset_id").asText())) {
                ids.add(item.get("asset_id").asText());
            }
        });
        return ids;
    }

    /** fixture source를 테스트 run에 묶는다: run·attempt·fence와 asset ID·content version 철자. */
    private static ObjectNode bind(JsonNode source, Run run) {
        ObjectNode bound = (ObjectNode) source.deepCopy();
        bound.put("run_id", run.runId().toString()).put("attempt", 1).put("fence_token", FENCE);
        bound.at("/payload/assistant_response/source_references").forEach(ref ->
                ((ObjectNode) ref).put("asset_id", String.valueOf(run.assets().get(ref.get("asset_id").asText()))));
        bound.at("/payload/selected_page_provenance").forEach(item -> ((ObjectNode) item)
                .put("asset_id", String.valueOf(run.assets().get(item.get("asset_id").asText())))
                .put("content_version", "1"));
        return bound;
    }

    private static SourceEvent event(JsonNode source) {
        return InternalRunWire.read(source, SourceEvent.class);
    }

    private static JsonNode renamed(JsonNode source, String from, String to) {
        try {
            return RAW.readTree(RAW.writeValueAsString(source).replace('"' + from + '"', '"' + to + '"'));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** fixture browser payload를 이 run 값으로: assistantMessageId와 sourceReferences[].assetId. */
    private static JsonNode expectedBrowserPayload(JsonNode fixture, Run run) {
        ObjectNode payload = (ObjectNode) fixture.at("/browser/payload").deepCopy();
        payload.put("assistantMessageId", run.assistantMessageId().toString());
        payload.at("/assistantResponse/sourceReferences").forEach(ref ->
                ((ObjectNode) ref).put("assetId", String.valueOf(run.assets().get(ref.get("assetId").asText()))));
        return payload;
    }

    private static JsonNode withMessageId(JsonNode payload, UUID messageId) {
        ObjectNode copy = (ObjectNode) payload.deepCopy();
        copy.put("assistantMessageId", messageId.toString());
        return copy;
    }

    /** SSE data처럼 문자열로 직렬화해 숫자 node 종류 차이를 없앤다. */
    private JsonNode normalize(JsonNode node) {
        try {
            return objectMapper.readTree(objectMapper.writeValueAsString(node));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static UsageCompensationReason expectedCompensation(JsonNode source) {
        JsonNode payload = source.get("payload");
        if (source.get("type").asText().equals("run.failed")) {
            return switch (payload.get("failure_class").asText()) {
                case "verification" -> UsageCompensationReason.VERIFICATION_FAILED;
                case "budget" -> UsageCompensationReason.BUDGET_EXCEEDED;
                case "attachment" -> UsageCompensationReason.INVALID_ATTACHMENT;
                default -> UsageCompensationReason.INFRASTRUCTURE_FAILURE;
            };
        }
        return switch (payload.get("response_disposition").asText()) {
            case "clarification_only" -> UsageCompensationReason.CLARIFICATION_ONLY;
            case "unsupported_only" -> UsageCompensationReason.UNSUPPORTED_ONLY;
            default -> null;
        };
    }

    /** run이 COMPLETED(답 확정·무보상) 또는 FAILED(답 없음·보상 1회) 중 하나로만 끝났다. */
    private void assertExactlyOneOutcome(Run run) {
        String status = (String) runRow(run).get("status");
        assertThat(status).isIn("COMPLETED", "FAILED");
        long solutions = count("SELECT count(*) FROM verified_solutions WHERE source_run_id = ?", run.runId());
        long terminals = count("""
                SELECT count(*) FROM ai_run_events WHERE run_id = ? AND event_type IN ('run.completed', 'run.failed')
                """, run.runId());
        assertThat(terminals).isEqualTo(1);
        if (status.equals("COMPLETED")) {
            assertThat(messageStatus(run)).isEqualTo("COMPLETED");
            assertThat(solutions).isEqualTo(1);
            assertThat(compensationReason(run)).isNull();
        } else {
            assertThat(messageStatus(run)).isEqualTo("FAILED");
            assertThat(solutions).isZero();
            assertThat(compensationReason(run)).isNotNull();
        }
    }

    private int cancel(UUID runId) {
        return tx.execute(status -> {
            // 1.84 취소 전환이 따를 잠금 순서(Note → run)
            jdbc.queryForList("SELECT n.note_id FROM notes n JOIN ai_runs r ON r.note_id = n.note_id "
                    + "WHERE r.run_id = ? FOR SHARE OF n", Long.class, runId);
            return jdbc.update("""
                    UPDATE ai_runs SET status = 'CANCELING', cancel_cause = 'user_requested',
                           cancel_requested_from = 'RUNNING', cancel_requested_at = now()
                    WHERE run_id = ? AND status IN ('RUNNING', 'RETRYING')
                    """, runId);
        });
    }

    private <T> List<T> race(int workers, IntFunction<Callable<T>> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            CyclicBarrier barrier = new CyclicBarrier(workers);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                Callable<T> work = task.apply(i);
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return work.call();
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

    /** run·message·solution·event·usage 상태 전체. 거부·rollback이 아무것도 바꾸지 않았는지 비교한다. */
    record Snapshot(Map<String, Object> run, Map<String, Object> message, long solutions, long events,
                    long compensations) {
    }

    private Snapshot snapshot(Run run) {
        return new Snapshot(runRow(run), message(run),
                count("SELECT count(*) FROM verified_solutions WHERE note_id = ?", run.noteId()),
                count("SELECT count(*) FROM ai_run_events WHERE run_id = ?", run.runId()),
                count("SELECT count(*) FROM request_usage_compensations WHERE request_id = ?", run.requestId()));
    }

    private Map<String, Object> runRow(Run run) {
        return jdbc.queryForMap("""
                SELECT status, attempt, fence_token, response_disposition, clarification_reason, failure_code,
                       failure_class, lease_owner, last_event_seq, terminal_source_sha256, completed_at::text
                FROM ai_runs WHERE run_id = ?
                """, run.runId());
    }

    private Map<String, Object> message(Run run) {
        return jdbc.queryForMap("""
                SELECT status, response_pages::text, source_references::text, plain_text, content_sha256,
                       verification_summary::text, completed_at::text
                FROM chat_messages WHERE message_id = ?
                """, run.assistantMessageId());
    }

    private String messageStatus(Run run) {
        return jdbc.queryForObject("SELECT status FROM chat_messages WHERE message_id = ?", String.class,
                run.assistantMessageId());
    }

    private String solutionContent(UUID solutionId) {
        return jdbc.queryForObject("""
                SELECT (to_jsonb(s) - 'status' - 'superseded_by_solution_id' - 'superseded_at')::text
                FROM verified_solutions s WHERE solution_id = ?
                """, String.class, solutionId);
    }

    private StoredRunEvent lastEvent(Run run) {
        List<StoredRunEvent> events = store.findAfter(run.runId(), 0, 100);
        return events.get(events.size() - 1);
    }

    private UsageCompensationReason compensationReason(Run run) {
        List<String> reasons = jdbc.queryForList("SELECT reason FROM request_usage_compensations WHERE request_id = ?",
                String.class, run.requestId());
        return reasons.isEmpty() ? null : UsageCompensationReason.valueOf(reasons.get(0));
    }

    private long balance() {
        return jdbc.queryForObject(
                "SELECT daily_free_credit + free_credit + paid_credit FROM credit_balance WHERE user_id = ?",
                Long.class, ALICE);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private static String observe(Connection connection, String sql) {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
