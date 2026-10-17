package com.proovy.domain.usage.service;

import com.proovy.domain.credit.entity.CreditBalance;
import com.proovy.domain.credit.entity.CreditChangeType;
import com.proovy.domain.credit.entity.CreditEventType;
import com.proovy.domain.credit.entity.CreditHistory;
import com.proovy.domain.credit.entity.CreditType;
import com.proovy.domain.credit.repository.CreditHistoryRepository;
import com.proovy.domain.credit.service.CreditBalanceService;
import com.proovy.domain.run.entity.AiRun;
import com.proovy.domain.usage.entity.RequestUsageCharge;
import com.proovy.domain.usage.entity.RequestUsageCompensation;
import com.proovy.domain.usage.entity.UsageCompensationReason;
import com.proovy.domain.usage.exception.InsufficientUsageBalanceException;
import com.proovy.domain.usage.exception.RequestUsageConflictException;
import com.proovy.domain.usage.repository.RequestUsageChargeRepository;
import com.proovy.domain.usage.repository.RequestUsageCompensationRepository;
import com.proovy.domain.usage.service.UsageCompensationResult.Outcome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 일반 AI 요청의 사용량 ledger 연산 (request_id당 최초 한 번 차감, 최대 한 번 보상).
 *
 * <p><b>transaction 참여 규칙</b>: 모든 연산은 {@link Propagation#MANDATORY}다. 호출자(접수 1.79·terminal 1.82·
 * 취소 1.84)가 연 transaction 안에서 run/message 전환과 함께 commit되거나 함께 rollback되며, 이 서비스가
 * 독립적으로 먼저 commit하는 경로는 없다. 예외를 던지면 호출자 transaction은 rollback-only가 된다.
 *
 * <p><b>잠금 순서</b>: 사용자 잔액 row({@code credit_balance FOR UPDATE})를 잡은 뒤 ledger를 조회·기록한다.
 * 같은 사용자의 차감·보상은 잔액 잠금으로 직렬화되고, ledger PK(request_id)가 최종 중복 방어다.
 * 호출자는 run 조건부 UPDATE 등 다른 row 잠금을 먼저 잡고 이 연산을 transaction 마지막 쪽에서 호출한다.
 *
 * <p><b>호출 경계</b>: 일반 AI 요청 차감은 접수 transaction의 {@link #chargeRequest}만 수행한다.
 * 구형 기능별 사후 차감·브라우저 임의 차감 API는 제거했고, 모델 승급·살아 있는 실행 재시도·상태 조회는
 * 이 서비스를 호출하지 않는다. provider 비용 정산(ai_runs.used_cost_micros 등)은 제품 사용량과 무관하다.
 */
@Slf4j
@Service
public class RequestUsageService {

    /** 일반 요청은 request당 한 단위 (docs/plan/07) */
    public static final int GENERAL_REQUEST_UNITS = 1;

    private final CreditBalanceService creditBalanceService;
    private final RequestUsageChargeRepository chargeRepository;
    private final RequestUsageCompensationRepository compensationRepository;
    private final CreditHistoryRepository creditHistoryRepository;
    private final int creditsPerUnit;

    public RequestUsageService(CreditBalanceService creditBalanceService,
                               RequestUsageChargeRepository chargeRepository,
                               RequestUsageCompensationRepository compensationRepository,
                               CreditHistoryRepository creditHistoryRepository,
                               @Value("${proovy.usage.credits-per-unit:10}") int creditsPerUnit) {
        if (creditsPerUnit <= 0) {
            throw new IllegalArgumentException("proovy.usage.credits-per-unit must be positive");
        }
        this.creditBalanceService = creditBalanceService;
        this.chargeRepository = chargeRepository;
        this.compensationRepository = compensationRepository;
        this.creditHistoryRepository = creditHistoryRepository;
        this.creditsPerUnit = creditsPerUnit;
    }

    /**
     * 접수 transaction에서 request 한 단위를 차감한다. run row는 같은 transaction에서 먼저 insert되어 있어야 한다
     * (차감 row의 run composite FK). 같은 request의 차감이 이미 있으면 잔액을 바꾸지 않고 기존 차감을 돌려준다.
     *
     * @throws InsufficientUsageBalanceException 잔액 부족. 차감·ledger·내역을 만들지 않는다.
     * @throws RequestUsageConflictException     기존 차감이 다른 사용자·run에 속한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UsageChargeResult chargeRequest(Long userId, String requestId, UUID runId) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(runId, "runId");

        CreditBalance balance = creditBalanceService.lockBalanceInCurrentTransaction(userId);

        Optional<RequestUsageCharge> existing = chargeRepository.findById(requestId);
        if (existing.isPresent()) {
            RequestUsageCharge charge = existing.get();
            if (!userId.equals(charge.getUserId()) || !runId.equals(charge.getRunId())) {
                throw new RequestUsageConflictException("request usage belongs to another user or run");
            }
            return new UsageChargeResult(false, charge);
        }

        int amount = GENERAL_REQUEST_UNITS * creditsPerUnit;
        int available = balance.getTotalAvailable();
        if (available < amount) {
            throw new InsufficientUsageBalanceException(amount, available);
        }

        int dailyBefore = balance.getDailyFreeCredit();
        int freeBefore = balance.getFreeCredit();
        int paidBefore = balance.getPaidCredit();
        balance.deductCredit(amount);

        RequestUsageCharge charge = chargeRepository.saveAndFlush(RequestUsageCharge.builder()
                .requestId(requestId)
                .userId(userId)
                .runId(runId)
                .usageUnits(GENERAL_REQUEST_UNITS)
                .chargedDaily(dailyBefore - balance.getDailyFreeCredit())
                .chargedFree(freeBefore - balance.getFreeCredit())
                .chargedPaid(paidBefore - balance.getPaidCredit())
                .chargedAt(now())
                .build());

        saveHistory(balance, CreditEventType.LLM_QUERY, CreditChangeType.SPEND, null,
                charge.getChargedDaily(), charge.getChargedFree(), charge.getChargedPaid());
        log.info("request usage charged: userId={}, requestId={}, amount={}", userId, requestId, amount);
        return new UsageChargeResult(true, charge);
    }

    /**
     * terminal 전환이 반영된 run의 분류로 보상 여부를 정하고, 대상이면 한 번 보상한다.
     * 호출자는 같은 transaction에서 run을 terminal로 바꾼 뒤(조건부 UPDATE가 이긴 경우에만) 호출한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UsageCompensationResult settleTerminal(AiRun run) {
        return settle(run.getUserId(), run.getRequestId(), RunTerminalOutcome.of(run));
    }

    /** {@link #settleTerminal(AiRun)}과 같고, run 엔티티 대신 terminal 분류를 직접 받는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UsageCompensationResult settle(Long userId, String requestId, RunTerminalOutcome outcome) {
        Optional<UsageCompensationReason> reason = UsageCompensationPolicy.decide(outcome);
        if (reason.isEmpty()) {
            return UsageCompensationResult.of(Outcome.NOT_COMPENSABLE, null);
        }
        return compensate(userId, requestId, reason.get());
    }

    /**
     * request의 차감을 한 번 보상한다. 버킷별 복구량은 차감량과 같다(restored_* = charged_*).
     * 이미 보상되었으면(다른 사유 포함) 잔액을 바꾸지 않고 먼저 적용된 사유를 돌려준다.
     *
     * @throws RequestUsageConflictException 차감이 다른 사용자에 속한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UsageCompensationResult compensate(Long userId, String requestId, UsageCompensationReason reason) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(reason, "reason");

        Optional<RequestUsageCharge> found = chargeRepository.findById(requestId);
        if (found.isEmpty()) {
            return UsageCompensationResult.of(Outcome.NOT_CHARGED, null);
        }
        RequestUsageCharge charge = found.get();
        if (!userId.equals(charge.getUserId())) {
            throw new RequestUsageConflictException("request usage belongs to another user");
        }

        CreditBalance balance = creditBalanceService.lockBalanceInCurrentTransaction(userId);

        Optional<RequestUsageCompensation> existing = compensationRepository.findById(requestId);
        if (existing.isPresent()) {
            return UsageCompensationResult.of(Outcome.ALREADY_COMPENSATED, existing.get().getReason());
        }

        balance.addDailyCredit(charge.getChargedDaily());
        balance.addFreeCredit(charge.getChargedFree());
        balance.addPaidCredit(charge.getChargedPaid());

        compensationRepository.saveAndFlush(RequestUsageCompensation.builder()
                .requestId(requestId)
                .reason(reason)
                .restoredDaily(charge.getChargedDaily())
                .restoredFree(charge.getChargedFree())
                .restoredPaid(charge.getChargedPaid())
                .compensatedAt(now())
                .build());

        saveHistory(balance, CreditEventType.LLM_QUERY_COMPENSATION, CreditChangeType.EARN, reason.getDescription(),
                charge.getChargedDaily(), charge.getChargedFree(), charge.getChargedPaid());
        log.info("request usage compensated: userId={}, requestId={}, reason={}", userId, requestId, reason);
        return UsageCompensationResult.of(Outcome.COMPENSATED, reason);
    }

    // 버킷별로 분리 기록해 내역 필터링이 왜곡되지 않게 한다 (기존 CreditHistory 규칙)
    private void saveHistory(CreditBalance balance, CreditEventType eventType, CreditChangeType changeType,
                             String description, int daily, int free, int paid) {
        saveHistoryItem(balance, eventType, changeType, description, daily, CreditType.DAILY);
        saveHistoryItem(balance, eventType, changeType, description, free, CreditType.FREE);
        saveHistoryItem(balance, eventType, changeType, description, paid, CreditType.PAID);
    }

    private void saveHistoryItem(CreditBalance balance, CreditEventType eventType, CreditChangeType changeType,
                                 String description, int amount, CreditType creditType) {
        if (amount <= 0) {
            return;
        }
        creditHistoryRepository.save(CreditHistory.builder()
                .user(balance.getUser())
                .eventType(eventType)
                .eventName(eventType.getDescription())
                .description(description)
                .amount(amount)
                .changeType(changeType)
                .creditType(creditType)
                .balanceAfterDaily(balance.getDailyFreeCredit())
                .balanceAfterFree(balance.getFreeCredit())
                .balanceAfterPaid(balance.getPaidCredit())
                .build());
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
