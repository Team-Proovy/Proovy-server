package com.proovy.domain.credit.service;

import com.proovy.domain.credit.dto.request.CreditUseRequest;
import com.proovy.domain.credit.dto.response.CreditBalanceResponse;
import com.proovy.domain.credit.dto.response.CreditUseResponse;
import com.proovy.domain.credit.entity.CreditBalance;
import com.proovy.domain.credit.entity.CreditChangeType;
import com.proovy.domain.credit.entity.CreditEventType;
import com.proovy.domain.credit.entity.CreditHistory;
import com.proovy.domain.credit.entity.CreditType;
import com.proovy.domain.credit.repository.CreditBalanceRepository;
import com.proovy.domain.credit.repository.CreditHistoryRepository;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreditUseService {

    private final CreditBalanceRepository creditBalanceRepository;
    private final CreditHistoryRepository creditHistoryRepository;

    // 기능별 기본 비용
    private static final Map<String, Integer> FEATURE_BASE_COST = Map.of(
            "Solve", 10,
            "Explain", 5,
            "CreateGraph", 5,
            "Variant", 5,
            "Solution", 20,
            "Check", 3
    );

    // 난이도별 배율
    private static final Map<String, Double> DIFFICULTY_MULTIPLIER = Map.of(
            "easy", 1.0,
            "medium", 1.5,
            "hard", 2.0
    );

    // 이벤트 타입별 고정 비용
    private static final Map<CreditEventType, Integer> EVENT_TYPE_COST = Map.of(
            CreditEventType.OCR, 10,
            CreditEventType.CODE_EXECUTION, 5
    );

    /**
     * 크레딧 잔액 조회
     */
    @Transactional(readOnly = true)
    public CreditBalanceResponse getBalance(Long userId, Integer checkCost) {
        CreditBalance balance = creditBalanceRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER4041));

        Integer totalAvailable = balance.getTotalAvailable();
        Boolean canUse = checkCost == null || totalAvailable >= checkCost;

        return CreditBalanceResponse.builder()
                .dailyFreeCredit(balance.getDailyFreeCredit())
                .dailyFreeLimit(balance.getDailyFreeLimit())
                .dailyExpiresAt(balance.getDailyExpiresAt())
                .freeCredit(balance.getFreeCredit())
                .paidCredit(balance.getPaidCredit())
                .paidExpiresAt(balance.getPaidExpiresAt())
                .totalAvailable(totalAvailable)
                .canUse(canUse)
                .checkedCost(checkCost)
                .build();
    }

    /**
     * 크레딧 사용 (차감)
     */
    @Transactional
    public CreditUseResponse useCredit(Long userId, CreditUseRequest request) {
        CreditBalance balance = creditBalanceRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER4041));

        // 비용 계산
        int cost = calculateCost(request);
        int totalBefore = balance.getTotalAvailable();

        // 잔액 부족 체크
        if (totalBefore < cost) {
            log.warn("크레딧 부족: userId={}, 필요={}, 잔액={}", userId, cost, totalBefore);
            return CreditUseResponse.builder()
                    .success(false)
                    .usedAmount(0)
                    .balance(buildBalanceDto(balance))
                    .insufficientCredit(true)
                    .message("크레딧이 부족합니다. 필요: " + cost + ", 잔액: " + totalBefore)
                    .build();
        }

        // 차감 전 잔액 기록 (히스토리용)
        int dailyBefore = balance.getDailyFreeCredit();
        int freeBefore = balance.getFreeCredit();
        int paidBefore = balance.getPaidCredit();

        // 크레딧 차감
        balance.deductCredit(cost);

        // 히스토리 기록
        saveCreditHistory(balance, request, dailyBefore, freeBefore, paidBefore);

        log.info("크레딧 사용 완료: userId={}, 사용={}, 잔액={}",
                userId, cost, balance.getTotalAvailable());

        return CreditUseResponse.builder()
                .success(true)
                .usedAmount(cost)
                .balance(buildBalanceDto(balance))
                .insufficientCredit(false)
                .message("크레딧이 차감되었습니다.")
                .build();
    }

    /**
     * 비용 계산
     */
    public int calculateCost(CreditUseRequest request) {
        // 직접 지정된 금액이 있으면 사용
        if (request.getAmount() != null && request.getAmount() > 0) {
            return request.getAmount();
        }

        // 이벤트 타입 기반 고정 비용
        String requestEventType = request.getEventType();
        if (requestEventType != null) {
            try {
                CreditEventType eventType = CreditEventType.valueOf(requestEventType);
                if (EVENT_TYPE_COST.containsKey(eventType)) {
                    return EVENT_TYPE_COST.get(eventType);
                }
            } catch (IllegalArgumentException ignored) {
                // 알 수 없는 이벤트 타입은 기능별 비용으로 계산
            }
        }

        // 기능 이름 기반 비용 계산 (난이도 적용)
        String featureName = request.getFeatureName();
        if (featureName != null && FEATURE_BASE_COST.containsKey(featureName)) {
            int baseCost = FEATURE_BASE_COST.get(featureName);
            String difficulty = request.getDifficulty();
            double multiplier = DIFFICULTY_MULTIPLIER.getOrDefault(
                    difficulty != null ? difficulty.toLowerCase() : "easy",
                    1.0
            );
            return (int) Math.ceil(baseCost * multiplier);
        }

        // 기본 비용 (LLM_QUERY 등)
        return 5;
    }

    /**
     * 특정 기능의 예상 비용 조회
     */
    public int getEstimatedCost(String featureName, String difficulty) {
        int baseCost = FEATURE_BASE_COST.getOrDefault(featureName, 5);
        double multiplier = DIFFICULTY_MULTIPLIER.getOrDefault(
                difficulty != null ? difficulty.toLowerCase() : "easy",
                1.0
        );
        return (int) Math.ceil(baseCost * multiplier);
    }

    private CreditUseResponse.BalanceDto buildBalanceDto(CreditBalance balance) {
        return CreditUseResponse.BalanceDto.builder()
                .dailyFreeCredit(balance.getDailyFreeCredit())
                .freeCredit(balance.getFreeCredit())
                .paidCredit(balance.getPaidCredit())
                .totalAvailable(balance.getTotalAvailable())
                .build();
    }

    private void saveCreditHistory(CreditBalance balance, CreditUseRequest request,
                                   int dailyBefore, int freeBefore, int paidBefore) {
        // 어떤 크레딧에서 차감되었는지 판단
        int dailyUsed = dailyBefore - balance.getDailyFreeCredit();
        int freeUsed = freeBefore - balance.getFreeCredit();
        int paidUsed = paidBefore - balance.getPaidCredit();

        CreditEventType eventType = CreditEventType.LLM_QUERY;
        String requestEventType = request.getEventType();
        if (requestEventType != null) {
            try {
                eventType = CreditEventType.valueOf(requestEventType);
            } catch (IllegalArgumentException ignored) {
                // 기본값 유지
            }
        }

        String eventName = request.getFeatureName() != null
                ? request.getFeatureName() + " 실행"
                : eventType.getDescription();

        // 유형별로 분리 기록해 필터링 왜곡을 방지합니다.
        saveHistoryItem(balance, eventType, eventName, request.getDescription(), dailyUsed, CreditType.DAILY);
        saveHistoryItem(balance, eventType, eventName, request.getDescription(), freeUsed, CreditType.FREE);
        saveHistoryItem(balance, eventType, eventName, request.getDescription(), paidUsed, CreditType.PAID);
    }

    private void saveHistoryItem(CreditBalance balance, CreditEventType eventType,
                                 String eventName, String description, int amount, CreditType creditType) {
        if (amount <= 0) {
            return;
        }

        CreditHistory history = CreditHistory.builder()
                .user(balance.getUser())
                .eventType(eventType)
                .eventName(eventName)
                .description(description)
                .amount(amount)
                .changeType(CreditChangeType.SPEND)
                .creditType(creditType)
                .balanceAfterDaily(balance.getDailyFreeCredit())
                .balanceAfterFree(balance.getFreeCredit())
                .balanceAfterPaid(balance.getPaidCredit())
                .build();

        creditHistoryRepository.save(history);
    }
}
