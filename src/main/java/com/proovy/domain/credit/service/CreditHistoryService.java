package com.proovy.domain.credit.service;

import com.proovy.domain.credit.dto.response.CreditHistoryResponse;
import com.proovy.domain.credit.entity.CreditBalance;
import com.proovy.domain.credit.entity.CreditChangeType;
import com.proovy.domain.credit.entity.CreditHistory;
import com.proovy.domain.credit.entity.CreditType;
import com.proovy.domain.credit.repository.CreditHistoryRepository;
import com.proovy.domain.credit.repository.CreditHistorySpecification;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CreditHistoryService {

    private final CreditBalanceService creditBalanceService;
    private final CreditHistoryRepository creditHistoryRepository;

    private static final int MAX_QUERY_DAYS = 90;
    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    @Transactional
    public CreditHistoryResponse getCreditHistory(
            Long userId,
            Integer page,
            Integer size,
            String changeTypeStr,
            String creditTypeStr,
            String startDateStr,
            String endDateStr
    ) {
        // 1. 페이지네이션 파라미터 검증
        int validatedPage = page != null ? Math.max(0, page) : 0;
        int validatedSize = validatePageSize(size);

        // 2. 날짜 파라미터 파싱 및 검증
        LocalDateTime startDate = parseDate(startDateStr, true);
        LocalDateTime endDate = parseDate(endDateStr, false);
        validateDateRange(startDate, endDate);

        // 3. Enum 변환
        CreditChangeType changeType = parseChangeType(changeTypeStr);
        CreditType creditType = parseCreditType(creditTypeStr);

        // 4. 크레딧 잔액 조회
        CreditBalance balance = creditBalanceService.getOrCreateBalance(userId);

        // 5. 크레딧 내역 조회
        Pageable pageable = PageRequest.of(validatedPage, validatedSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<CreditHistory> historyPage = creditHistoryRepository.findAll(
                CreditHistorySpecification.withFilters(userId, changeType, creditType, startDate, endDate),
                pageable
        );

        // 6. 기간 통계 조회
        LocalDateTime summaryStartDate = startDate != null ? startDate : LocalDateTime.of(2020, 1, 1, 0, 0);
        LocalDateTime summaryEndDate = endDate != null ? endDate : LocalDateTime.now().plusDays(1);

        CreditHistoryRepository.CreditPeriodSummary periodSummary =
                creditHistoryRepository.calculatePeriodSummary(
                        userId,
                        CreditChangeType.EARN,
                        CreditChangeType.SPEND,
                        CreditChangeType.EXPIRE,
                        summaryStartDate,
                        summaryEndDate
                );

        // 7. DTO 변환 및 반환
        return buildResponse(balance, historyPage, periodSummary, startDateStr, endDateStr);
    }

    private int validatePageSize(Integer size) {
        if (size == null) return DEFAULT_PAGE_SIZE;
        if (size < MIN_PAGE_SIZE) return MIN_PAGE_SIZE;
        if (size > MAX_PAGE_SIZE) return MAX_PAGE_SIZE;
        return size;
    }

    private LocalDateTime parseDate(String dateStr, boolean isStartDate) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }

        try {
            LocalDate date = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
            return isStartDate ? date.atStartOfDay() : date.plusDays(1).atStartOfDay();
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.CREDIT4001);
        }
    }

    private void validateDateRange(LocalDateTime startDate, LocalDateTime endDate) {
        // 둘 다 없으면 OK
        if (startDate == null && endDate == null) {
            return;
        }

        // 한쪽만 있으면 에러 (90일 제한 우회 방지)
        if (startDate == null || endDate == null) {
            throw new BusinessException(ErrorCode.CREDIT4001, "조회 기간은 시작일과 종료일을 모두 입력해야 합니다.");
        }

        if (!startDate.isBefore(endDate)) {
            throw new BusinessException(ErrorCode.CREDIT4001, "시작일은 종료일보다 이전이어야 합니다.");
        }

        long daysBetween = ChronoUnit.DAYS.between(startDate, endDate);
        if (daysBetween > MAX_QUERY_DAYS) {
            throw new BusinessException(ErrorCode.CREDIT4002);
        }
    }

    private CreditChangeType parseChangeType(String changeTypeStr) {
        if (changeTypeStr == null || changeTypeStr.equalsIgnoreCase("all")) {
            return null;
        }

        try {
            return CreditChangeType.valueOf(changeTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.COMMON400, "유효하지 않은 changeType입니다.");
        }
    }

    private CreditType parseCreditType(String creditTypeStr) {
        if (creditTypeStr == null || creditTypeStr.equalsIgnoreCase("all")) {
            return null;
        }

        try {
            return CreditType.valueOf(creditTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.COMMON400, "유효하지 않은 creditType입니다.");
        }
    }

    private CreditHistoryResponse buildResponse(
            CreditBalance balance,
            Page<CreditHistory> historyPage,
            CreditHistoryRepository.CreditPeriodSummary periodSummary,
            String startDateStr,
            String endDateStr
    ) {
        // 크레딧 요약
        CreditHistoryResponse.CreditSummaryDto creditSummary = CreditHistoryResponse.CreditSummaryDto.builder()
                .dailyFreeCredit(CreditHistoryResponse.DailyFreeCreditDto.builder()
                        .balance(balance.getDailyFreeCredit())
                        .limit(balance.getDailyFreeLimit())
                        .expiresAt(balance.getDailyExpiresAt())
                        .build())
                .freeCredit(CreditHistoryResponse.FreeCreditDto.builder()
                        .balance(balance.getFreeCredit())
                        .build())
                .paidCredit(CreditHistoryResponse.PaidCreditDto.builder()
                        .balance(balance.getPaidCredit())
                        .expiresAt(balance.getPaidExpiresAt())
                        .build())
                .totalAvailable(balance.getTotalAvailable())
                .build();

        // 내역 변환
        List<CreditHistoryResponse.CreditHistoryItemDto> historyItems = historyPage.getContent().stream()
                .map(history -> CreditHistoryResponse.CreditHistoryItemDto.builder()
                        .historyId(history.getId())
                        .eventType(history.getEventType().name())
                        .eventName(history.getEventName())
                        .description(history.getDescription())
                        .amount(history.getAmount())
                        .changeType(history.getChangeType().name())
                        .creditType(history.getCreditType().name())
                        .balanceAfter(CreditHistoryResponse.BalanceAfterDto.builder()
                                .daily(history.getBalanceAfterDaily())
                                .free(history.getBalanceAfterFree())
                                .paid(history.getBalanceAfterPaid())
                                .build())
                        .createdAt(history.getCreatedAt())
                        .build())
                .collect(Collectors.toList());

        CreditHistoryResponse.PageInfoDto pageInfo = CreditHistoryResponse.PageInfoDto.builder()
                .page(historyPage.getNumber())
                .size(historyPage.getSize())
                .totalElements(historyPage.getTotalElements())
                .totalPages(historyPage.getTotalPages())
                .hasNext(historyPage.hasNext())
                .build();

        CreditHistoryResponse.HistoryPageDto historyDto = CreditHistoryResponse.HistoryPageDto.builder()
                .content(historyItems)
                .pageInfo(pageInfo)
                .build();

        // 기간 통계 (null 안전 처리)
        CreditHistoryResponse.PeriodSummaryDto periodSummaryDto = CreditHistoryResponse.PeriodSummaryDto.builder()
                .totalEarned(periodSummary != null && periodSummary.getTotalEarned() != null ? periodSummary.getTotalEarned() : 0L)
                .totalSpent(periodSummary != null && periodSummary.getTotalSpent() != null ? periodSummary.getTotalSpent() : 0L)
                .totalExpired(periodSummary != null && periodSummary.getTotalExpired() != null ? periodSummary.getTotalExpired() : 0L)
                .periodStart(startDateStr != null ? startDateStr : "")
                .periodEnd(endDateStr != null ? endDateStr : "")
                .build();

        return CreditHistoryResponse.builder()
                .creditSummary(creditSummary)
                .history(historyDto)
                .periodSummary(periodSummaryDto)
                .build();
    }
}
