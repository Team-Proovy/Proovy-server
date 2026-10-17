package com.proovy.domain.usage.service;

import com.proovy.domain.run.repository.AiRunRepository;
import com.proovy.domain.usage.entity.UsageCompensationReason;
import com.proovy.domain.usage.exception.InsufficientUsageBalanceException;
import com.proovy.domain.usage.exception.RequestUsageConflictException;
import com.proovy.domain.usage.service.UsageCompensationResult.Outcome;
import com.proovy.support.db.PostgresTestDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.proovy.support.db.ProductRows.ALICE;
import static com.proovy.support.db.ProductRows.ALICE_NOTE;
import static com.proovy.support.db.ProductRows.BOB;
import static com.proovy.support.db.ProductRows.BOB_NOTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 Postgres에서 요청 사용량 ledger 연산을 검사한다: request당 1회 차감, 잔액 부족 무부작용,
 * 최대 1회 보상, 동시 차감·보상·사유 경합, 상위 transaction rollback, 정책표별 순사용량.
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
        "proovy.usage.credits-per-unit=10"
})
class RequestUsageServicePostgresTest {

    private static final int UNIT = 10;
    private static final long CAROL = 1003L;
    private static final AtomicLong REQUEST_SEQ = new AtomicLong();

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

    @Autowired RequestUsageService usageService;
    @Autowired AiRunRepository aiRunRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetBalances() {
        // 다음 일일 리셋이 테스트 중 일어나지 않도록 만료 시각을 미래로 둔다
        jdbc.update("""
                UPDATE credit_balance SET daily_free_credit = 100, daily_free_limit = 100, free_credit = 0,
                       paid_credit = 1000, daily_expires_at = now() + interval '1 day'
                """);
    }

    // ------------------------------------------------------------------
    // 차감
    // ------------------------------------------------------------------

    @Test
    void chargesOneUnitOnceAndReplaysWithoutSecondDeduction() {
        String requestId = nextRequestId();
        Balance before = balance(ALICE);

        UUID runId = tx.execute(status -> {
            UUID run = insertPendingRun(requestId, ALICE, ALICE_NOTE);
            UsageChargeResult first = usageService.chargeRequest(ALICE, requestId, run);
            assertThat(first.newlyCharged()).isTrue();
            assertThat(first.charge().getUsageUnits()).isEqualTo(1);
            assertThat(first.charge().getCreditAmount()).isEqualTo(UNIT);
            // 같은 transaction 안에서 다시 호출해도 추가 차감이 없다
            assertThat(usageService.chargeRequest(ALICE, requestId, run).newlyCharged()).isFalse();
            return run;
        });

        // 재전송·재시도: 새 transaction에서 같은 request
        UsageChargeResult replay = tx.execute(status -> usageService.chargeRequest(ALICE, requestId, runId));
        assertThat(replay.newlyCharged()).isFalse();
        assertThat(replay.charge().getRunId()).isEqualTo(runId);

        assertThat(balance(ALICE)).isEqualTo(before.minusDaily(UNIT));
        assertThat(count("SELECT count(*) FROM request_usage_charges WHERE request_id = ?", requestId)).isEqualTo(1);
        assertThat(jdbc.queryForMap("""
                SELECT charged_daily, charged_free, charged_paid, credit_amount FROM request_usage_charges WHERE request_id = ?
                """, requestId)).isEqualTo(Map.of("charged_daily", UNIT, "charged_free", 0, "charged_paid", 0,
                "credit_amount", UNIT));
        assertThat(count("""
                SELECT count(*) FROM credit_history WHERE user_id = ? AND event_type = 'LLM_QUERY'
                  AND change_type = 'SPEND' AND credit_type = 'DAILY' AND amount = ?
                """, ALICE, UNIT)).isGreaterThanOrEqualTo(1);
        finishRun(runId);
    }

    @Test
    void operationsRequireCallerTransaction() {
        assertThatThrownBy(() -> usageService.chargeRequest(ALICE, nextRequestId(), UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> usageService.compensate(ALICE, nextRequestId(), UsageCompensationReason.PENDING_CANCELED))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void insufficientBalanceRejectsWithoutAnySideEffect() {
        jdbc.update("UPDATE credit_balance SET daily_free_credit = 4, free_credit = 3, paid_credit = 2 WHERE user_id = ?", BOB);
        String requestId = nextRequestId();
        long runsBefore = count("SELECT count(*) FROM ai_runs WHERE user_id = ?", BOB);
        long historyBefore = count("SELECT count(*) FROM credit_history WHERE user_id = ?", BOB);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            UUID run = insertPendingRun(requestId, BOB, BOB_NOTE);
            usageService.chargeRequest(BOB, requestId, run);
        })).isInstanceOf(InsufficientUsageBalanceException.class)
                .satisfies(e -> {
                    InsufficientUsageBalanceException ex = (InsufficientUsageBalanceException) e;
                    assertThat(ex.getRequired()).isEqualTo(UNIT);
                    assertThat(ex.getAvailable()).isEqualTo(9);
                });

        // 호출자가 예외를 삼켜도 transaction은 rollback-only라 commit되지 않는다
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            UUID run = insertPendingRun(requestId, BOB, BOB_NOTE);
            try {
                usageService.chargeRequest(BOB, requestId, run);
            } catch (InsufficientUsageBalanceException ignored) {
                // 삼킨다
            }
        })).isInstanceOf(UnexpectedRollbackException.class);

        assertThat(balance(BOB)).isEqualTo(new Balance(4, 3, 2));
        assertThat(count("SELECT count(*) FROM request_usage_charges WHERE request_id = ?", requestId)).isZero();
        assertThat(count("SELECT count(*) FROM ai_runs WHERE user_id = ?", BOB)).isEqualTo(runsBefore);
        assertThat(count("SELECT count(*) FROM credit_history WHERE user_id = ?", BOB)).isEqualTo(historyBefore);
    }

    @Test
    void chargesBucketsInPriorityOrderAndRestoresExactlyTheSameBuckets() {
        jdbc.update("UPDATE credit_balance SET daily_free_credit = 4, free_credit = 3, paid_credit = 50 WHERE user_id = ?", BOB);
        String requestId = nextRequestId();

        UUID runId = tx.execute(status -> {
            UUID run = insertPendingRun(requestId, BOB, BOB_NOTE);
            usageService.chargeRequest(BOB, requestId, run);
            return run;
        });
        assertThat(balance(BOB)).isEqualTo(new Balance(0, 0, 47));
        assertThat(jdbc.queryForMap("SELECT charged_daily, charged_free, charged_paid FROM request_usage_charges WHERE request_id = ?",
                requestId)).isEqualTo(Map.of("charged_daily", 4, "charged_free", 3, "charged_paid", 3));

        UsageCompensationResult result = tx.execute(status ->
                usageService.compensate(BOB, requestId, UsageCompensationReason.INFRASTRUCTURE_FAILURE));
        assertThat(result.outcome()).isEqualTo(Outcome.COMPENSATED);
        assertThat(balance(BOB)).isEqualTo(new Balance(4, 3, 50));
        assertThat(jdbc.queryForMap("SELECT restored_daily, restored_free, restored_paid, credit_amount FROM request_usage_compensations WHERE request_id = ?",
                requestId)).isEqualTo(Map.of("restored_daily", 4, "restored_free", 3, "restored_paid", 3, "credit_amount", UNIT));
        assertThat(count("""
                SELECT count(*) FROM credit_history WHERE user_id = ? AND event_type = 'LLM_QUERY_COMPENSATION'
                  AND change_type = 'EARN' AND description = ?
                """, BOB, UsageCompensationReason.INFRASTRUCTURE_FAILURE.getDescription())).isEqualTo(3);
        finishRun(runId);
    }

    @Test
    void missingBalanceRowIsCreatedInsideCallerTransactionOnly() {
        jdbc.update("INSERT INTO users (user_id, nickname, provider, provider_user_id) VALUES (?, 'carol', 'KAKAO', 'kakao-1003') ON CONFLICT DO NOTHING", CAROL);
        jdbc.update("INSERT INTO notes (note_id, user_id, title) VALUES (2004, ?, 'carol note') ON CONFLICT DO NOTHING", CAROL);
        jdbc.update("DELETE FROM credit_balance WHERE user_id = ?", CAROL);
        String requestId = nextRequestId();

        // 상위 transaction rollback: 잔액 row도 독립 commit되지 않는다
        tx.executeWithoutResult(status -> {
            UUID run = insertPendingRun(requestId, CAROL, 2004L);
            assertThat(usageService.chargeRequest(CAROL, requestId, run).newlyCharged()).isTrue();
            status.setRollbackOnly();
        });
        assertThat(count("SELECT count(*) FROM credit_balance WHERE user_id = ?", CAROL)).isZero();
        assertThat(count("SELECT count(*) FROM request_usage_charges WHERE request_id = ?", requestId)).isZero();

        UUID runId = tx.execute(status -> {
            UUID run = insertPendingRun(requestId, CAROL, 2004L);
            usageService.chargeRequest(CAROL, requestId, run);
            return run;
        });
        // FREE 플랜 일일 한도로 만들어진 뒤 한 단위 차감
        assertThat(balance(CAROL)).isEqualTo(new Balance(100 - UNIT, 0, 0));
        finishRun(runId);
    }

    @Test
    void conflictingOwnerOrRunIsRejected() {
        String requestId = nextRequestId();
        UUID runId = tx.execute(status -> {
            UUID run = insertPendingRun(requestId, ALICE, ALICE_NOTE);
            usageService.chargeRequest(ALICE, requestId, run);
            return run;
        });
        Balance aliceBefore = balance(ALICE);
        Balance bobBefore = balance(BOB);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> usageService.chargeRequest(BOB, requestId, runId)))
                .isInstanceOf(RequestUsageConflictException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                usageService.chargeRequest(ALICE, requestId, UUID.randomUUID())))
                .isInstanceOf(RequestUsageConflictException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                usageService.compensate(BOB, requestId, UsageCompensationReason.PENDING_CANCELED)))
                .isInstanceOf(RequestUsageConflictException.class);

        assertThat(balance(ALICE)).isEqualTo(aliceBefore);
        assertThat(balance(BOB)).isEqualTo(bobBefore);
        assertThat(count("SELECT count(*) FROM request_usage_compensations WHERE request_id = ?", requestId)).isZero();
        finishRun(runId);
    }

    // ------------------------------------------------------------------
    // 보상
    // ------------------------------------------------------------------

    @Test
    void compensationWithoutChargeIsNoop() {
        Balance before = balance(ALICE);
        UsageCompensationResult result = tx.execute(status ->
                usageService.compensate(ALICE, nextRequestId(), UsageCompensationReason.CLARIFICATION_ONLY));
        assertThat(result.outcome()).isEqualTo(Outcome.NOT_CHARGED);
        assertThat(balance(ALICE)).isEqualTo(before);
    }

    @Test
    void compensatesAtMostOnceAndKeepsTheFirstReason() {
        String requestId = nextRequestId();
        UUID runId = chargedPendingRun(requestId, ALICE, ALICE_NOTE);
        Balance charged = balance(ALICE);

        UsageCompensationResult first = tx.execute(status ->
                usageService.compensate(ALICE, requestId, UsageCompensationReason.PENDING_CANCELED));
        UsageCompensationResult second = tx.execute(status ->
                usageService.compensate(ALICE, requestId, UsageCompensationReason.INFRASTRUCTURE_FAILURE));

        assertThat(first).isEqualTo(new UsageCompensationResult(Outcome.COMPENSATED, UsageCompensationReason.PENDING_CANCELED));
        assertThat(second).isEqualTo(new UsageCompensationResult(Outcome.ALREADY_COMPENSATED, UsageCompensationReason.PENDING_CANCELED));
        assertThat(balance(ALICE)).isEqualTo(charged.plusDaily(UNIT));
        assertThat(count("SELECT count(*) FROM request_usage_compensations WHERE request_id = ?", requestId)).isEqualTo(1);
        finishRun(runId);
    }

    @Test
    void callerRollbackUndoesCompensationSoItCanBeAppliedLater() {
        String requestId = nextRequestId();
        UUID runId = chargedPendingRun(requestId, ALICE, ALICE_NOTE);
        Balance charged = balance(ALICE);

        // terminal transaction이 다른 이유로 실패하면 보상도 남지 않는다
        tx.executeWithoutResult(status -> {
            assertThat(usageService.compensate(ALICE, requestId, UsageCompensationReason.VERIFICATION_FAILED)
                    .compensatedNow()).isTrue();
            status.setRollbackOnly();
        });
        assertThat(balance(ALICE)).isEqualTo(charged);
        assertThat(count("SELECT count(*) FROM request_usage_compensations WHERE request_id = ?", requestId)).isZero();

        UsageCompensationResult retried = tx.execute(status ->
                usageService.compensate(ALICE, requestId, UsageCompensationReason.VERIFICATION_FAILED));
        assertThat(retried.outcome()).isEqualTo(Outcome.COMPENSATED);
        assertThat(balance(ALICE)).isEqualTo(charged.plusDaily(UNIT));
        finishRun(runId);
    }

    // ------------------------------------------------------------------
    // 경합
    // ------------------------------------------------------------------

    @Test
    void concurrentDuplicateChargesDeductOnce() throws Exception {
        String requestId = nextRequestId();
        UUID runId = tx.execute(status -> insertPendingRun(requestId, ALICE, ALICE_NOTE));
        Balance before = balance(ALICE);

        List<UsageChargeResult> results = race(4, i -> () ->
                tx.execute(status -> usageService.chargeRequest(ALICE, requestId, runId)));

        assertThat(results).filteredOn(UsageChargeResult::newlyCharged).hasSize(1);
        assertThat(balance(ALICE)).isEqualTo(before.minusDaily(UNIT));
        assertThat(count("SELECT count(*) FROM request_usage_charges WHERE request_id = ?", requestId)).isEqualTo(1);
        finishRun(runId);
    }

    @Test
    void concurrentCompensationReasonsApplyExactlyOnce() throws Exception {
        String requestId = nextRequestId();
        UUID runId = chargedPendingRun(requestId, ALICE, ALICE_NOTE);
        Balance charged = balance(ALICE);
        long historyBefore = count("SELECT count(*) FROM credit_history WHERE user_id = ? AND event_type = 'LLM_QUERY_COMPENSATION'", ALICE);
        UsageCompensationReason[] reasons = {
                UsageCompensationReason.PENDING_CANCELED, UsageCompensationReason.CLARIFICATION_ONLY,
                UsageCompensationReason.INFRASTRUCTURE_FAILURE, UsageCompensationReason.BUDGET_EXCEEDED};

        List<UsageCompensationResult> results = race(reasons.length, i -> () ->
                tx.execute(status -> usageService.compensate(ALICE, requestId, reasons[i])));

        List<UsageCompensationResult> winners = results.stream().filter(UsageCompensationResult::compensatedNow).toList();
        assertThat(winners).hasSize(1);
        UsageCompensationReason winner = winners.get(0).reason();
        assertThat(results).filteredOn(r -> !r.compensatedNow())
                .allSatisfy(r -> assertThat(r).isEqualTo(new UsageCompensationResult(Outcome.ALREADY_COMPENSATED, winner)));
        assertThat(balance(ALICE)).isEqualTo(charged.plusDaily(UNIT));
        assertThat(jdbc.queryForObject("SELECT reason FROM request_usage_compensations WHERE request_id = ?", String.class, requestId))
                .isEqualTo(winner.name());
        assertThat(count("SELECT count(*) FROM credit_history WHERE user_id = ? AND event_type = 'LLM_QUERY_COMPENSATION'", ALICE))
                .isEqualTo(historyBefore + 1);
        finishRun(runId);
    }

    @Test
    void concurrentChargesOfDifferentRequestsKeepBalanceConsistent() throws Exception {
        // 같은 사용자의 서로 다른 request 동시 차감: 잔액 잠금으로 직렬화되어 lost update가 없다
        jdbc.update("UPDATE credit_balance SET daily_free_credit = 15, free_credit = 0, paid_credit = 10 WHERE user_id = ?", BOB);
        long[] notes = {BOB_NOTE, insertNote(BOB, "bob race 1"), insertNote(BOB, "bob race 2")};
        String[] requests = {nextRequestId(), nextRequestId(), nextRequestId()};
        UUID[] runs = new UUID[3];
        for (int i = 0; i < 3; i++) {
            int n = i;
            runs[i] = tx.execute(status -> insertPendingRun(requests[n], BOB, notes[n]));
        }

        List<Object> results = race(3, i -> () -> {
            try {
                return tx.execute(status -> usageService.chargeRequest(BOB, requests[i], runs[i]));
            } catch (InsufficientUsageBalanceException e) {
                return e;
            }
        });

        // 잔액 25 = 두 요청만 차감 가능
        assertThat(results).filteredOn(r -> r instanceof UsageChargeResult).hasSize(2);
        assertThat(results).filteredOn(r -> r instanceof InsufficientUsageBalanceException).hasSize(1);
        assertThat(balance(BOB).total()).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COALESCE(sum(credit_amount), 0) FROM request_usage_charges WHERE request_id = ANY (?)",
                Integer.class, (Object) requests)).isEqualTo(2 * UNIT);
        for (UUID run : runs) {
            finishRun(run);
        }
    }

    // ------------------------------------------------------------------
    // 정책표: terminal 전환과 같은 transaction에서 settle
    // ------------------------------------------------------------------

    private record Scenario(String name, String transitionSql, int expectedNetUnits) {
    }

    private static final String RELEASE = ", completed_at = now(), lease_owner = NULL, lease_expires_at = NULL ";

    private static List<Scenario> scenarios() {
        List<Scenario> list = new ArrayList<>();
        String completed = "UPDATE ai_runs SET status = 'COMPLETED', response_disposition = ";
        list.add(new Scenario("substantive (혼합·자료 부재 안내 포함)", CLAIM + completed + "'substantive'" + RELEASE + WHERE, 1));
        for (String reason : new String[]{"missing_input", "ambiguous_target", "limited_pages", "corpus_not_ready",
                "page_range_required", "page_range_unreadable"}) {
            list.add(new Scenario("clarification_only/" + reason,
                    CLAIM + completed + "'clarification_only', clarification_reason = '" + reason + "'" + RELEASE + WHERE, 0));
        }
        list.add(new Scenario("unsupported_only", CLAIM + completed + "'unsupported_only'" + RELEASE + WHERE, 0));
        String[][] failures = {{"verification_failed", "verification"}, {"budget_exceeded", "budget"},
                {"invalid_attachment", "attachment"}, {"provider_error", "infrastructure"}, {"timeout", "infrastructure"},
                {"storage_error", "infrastructure"}, {"internal_error", "infrastructure"}};
        for (String[] f : failures) {
            list.add(new Scenario("failed/" + f[0], CLAIM + "UPDATE ai_runs SET status = 'FAILED', failure_code = '" + f[0]
                    + "', failure_class = '" + f[1] + "'" + RELEASE + WHERE, 0));
        }
        // PENDING에서 바로 최종 실패 (dispatch 전 인프라 실패)
        list.add(new Scenario("failed/from-pending", "UPDATE ai_runs SET status = 'FAILED', failure_code = 'internal_error',"
                + " failure_class = 'infrastructure', completed_at = now()" + WHERE, 0));
        for (String cause : new String[]{"user_requested", "asset_deleting", "budget_exhausted"}) {
            list.add(new Scenario("canceled/PENDING/" + cause, cancel("PENDING", cause), 0));
        }
        for (String from : new String[]{"RUNNING", "RETRYING"}) {
            list.add(new Scenario("canceled/" + from + "/user_requested", cancel(from, "user_requested"), 1));
            list.add(new Scenario("canceled/" + from + "/asset_deleting", cancel(from, "asset_deleting"), 1));
            list.add(new Scenario("canceled/" + from + "/budget_exhausted", cancel(from, "budget_exhausted"), 0));
        }
        return list;
    }

    private static final String WHERE = " WHERE run_id = ?;";
    private static final String CLAIM = """
            UPDATE ai_runs SET status = 'RUNNING', attempt = attempt + 1, fence_token = 'fence-' || (attempt + 1),
                   started_at = now(), lease_owner = 'coordinator-1', lease_expires_at = now() + interval '30 seconds'
            WHERE run_id = ?;""";
    private static final String RETRY = "UPDATE ai_runs SET status = 'RETRYING', retry_count = retry_count + 1 WHERE run_id = ?;";

    private static String cancel(String from, String cause) {
        String fields = "cancel_cause = '" + cause + "', cancel_requested_from = '" + from + "', cancel_requested_at = now()";
        if (from.equals("PENDING")) {
            return "UPDATE ai_runs SET status = 'CANCELED', " + fields + ", completed_at = now()" + WHERE;
        }
        String toExecution = from.equals("RETRYING") ? CLAIM + RETRY : CLAIM;
        return toExecution
                + "UPDATE ai_runs SET status = 'CANCELING', " + fields + WHERE
                + "UPDATE ai_runs SET status = 'CANCELED'" + RELEASE + WHERE;
    }

    @Test
    void netUsageMatchesPolicyTableForEveryTerminal() {
        List<String> mismatches = new ArrayList<>();
        for (Scenario scenario : scenarios()) {
            String requestId = nextRequestId();
            Balance before = balance(ALICE);
            UUID runId = chargedPendingRun(requestId, ALICE, ALICE_NOTE);

            UsageCompensationResult result = tx.execute(status -> {
                for (String sql : scenario.transitionSql().split(";")) {
                    if (!sql.isBlank()) {
                        assertThat(jdbc.update(sql, runId)).as(scenario.name() + ": " + sql).isEqualTo(1);
                    }
                }
                return usageService.settleTerminal(aiRunRepository.findById(runId).orElseThrow());
            });
            // terminal 처리 재전달·중복 settle은 추가 보상을 만들지 않는다
            UsageCompensationResult again = tx.execute(status ->
                    usageService.settleTerminal(aiRunRepository.findById(runId).orElseThrow()));

            int netCredits = before.total() - balance(ALICE).total();
            int expectedCredits = scenario.expectedNetUnits() * UNIT;
            Outcome expectedFirst = scenario.expectedNetUnits() == 1 ? Outcome.NOT_COMPENSABLE : Outcome.COMPENSATED;
            Outcome expectedAgain = scenario.expectedNetUnits() == 1 ? Outcome.NOT_COMPENSABLE : Outcome.ALREADY_COMPENSATED;
            if (netCredits != expectedCredits || result.outcome() != expectedFirst || again.outcome() != expectedAgain) {
                mismatches.add(scenario.name() + ": net=" + netCredits + " first=" + result.outcome() + " again=" + again.outcome());
            }
        }
        assertThat(mismatches).isEmpty();
    }

    @Test
    void promotionRetriesAndStatusReadsDoNotChargeAgain() {
        String requestId = nextRequestId();
        UUID runId = chargedPendingRun(requestId, ALICE, ALICE_NOTE);
        Balance charged = balance(ALICE);

        // 살아 있는 실행의 재시도·모델 승급(새 attempt/fence)·누적 예산 증가·상태 조회
        tx.executeWithoutResult(status -> {
            jdbc.update(CLAIM.replace(";", ""), runId);
            jdbc.update(RETRY.replace(";", ""), runId);
            jdbc.update(CLAIM.replace(";", ""), runId);
            jdbc.update("UPDATE ai_runs SET used_llm_calls = 5, used_cost_micros = 12345 WHERE run_id = ?", runId);
        });
        aiRunRepository.findByRequestId(requestId).orElseThrow();
        // 접수 재전송이 같은 연산을 다시 불러도 기존 차감을 돌려준다
        assertThat(tx.execute(status -> usageService.chargeRequest(ALICE, requestId, runId)).newlyCharged()).isFalse();

        assertThat(aiRunRepository.findById(runId).orElseThrow().getAttempt()).isEqualTo(2);
        assertThat(balance(ALICE)).isEqualTo(charged);
        assertThat(count("SELECT count(*) FROM request_usage_charges WHERE run_id = ?", runId)).isEqualTo(1);
        tx.executeWithoutResult(status -> jdbc.update(
                "UPDATE ai_runs SET status = 'COMPLETED', response_disposition = 'substantive'" + RELEASE + "WHERE run_id = ?", runId));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private record Balance(int daily, int free, int paid) {
        int total() {
            return daily + free + paid;
        }

        Balance minusDaily(int amount) {
            return new Balance(daily - amount, free, paid);
        }

        Balance plusDaily(int amount) {
            return new Balance(daily + amount, free, paid);
        }
    }

    private Balance balance(long userId) {
        return jdbc.queryForObject(
                "SELECT daily_free_credit, free_credit, paid_credit FROM credit_balance WHERE user_id = ?",
                (rs, n) -> new Balance(rs.getInt(1), rs.getInt(2), rs.getInt(3)), userId);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private static String nextRequestId() {
        return "req-usage-" + REQUEST_SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private long insertNote(long userId, String title) {
        Long id = jdbc.queryForObject("INSERT INTO notes (user_id, title) VALUES (?, ?) RETURNING note_id", Long.class, userId, title);
        return id == null ? 0 : id;
    }

    /** 접수 transaction의 run 부분 (user message·placeholder·PENDING run). */
    private UUID insertPendingRun(String requestId, long userId, long noteId) {
        UUID userMessageId = UUID.randomUUID();
        UUID assistantMessageId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
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
                """, runId, requestId, userId, noteId, userMessageId, assistantMessageId, "f".repeat(64));
        return runId;
    }

    private UUID chargedPendingRun(String requestId, long userId, long noteId) {
        return tx.execute(status -> {
            UUID run = insertPendingRun(requestId, userId, noteId);
            assertThat(usageService.chargeRequest(userId, requestId, run).newlyCharged()).isTrue();
            return run;
        });
    }

    /** Note의 Active Run 자리를 비운다 (다음 테스트가 같은 Note를 쓴다). 사용량에는 영향이 없다. */
    private void finishRun(UUID runId) {
        tx.executeWithoutResult(status -> jdbc.update("""
                UPDATE ai_runs SET status = 'CANCELED', cancel_cause = 'user_requested', cancel_requested_from = 'PENDING',
                       cancel_requested_at = now(), completed_at = now()
                WHERE run_id = ? AND status = 'PENDING'
                """, runId));
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
            for (Future<T> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
