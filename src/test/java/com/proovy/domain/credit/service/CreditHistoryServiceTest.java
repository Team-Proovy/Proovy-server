package com.proovy.domain.credit.service;

import com.proovy.domain.credit.dto.response.CreditHistoryResponse;
import com.proovy.domain.credit.entity.CreditBalance;
import com.proovy.domain.credit.entity.CreditHistory;
import com.proovy.domain.credit.entity.CreditChangeType;
import com.proovy.domain.credit.entity.CreditEventType;
import com.proovy.domain.credit.entity.CreditType;
import com.proovy.domain.credit.repository.CreditHistoryRepository;
import com.proovy.domain.user.entity.User;
import com.proovy.global.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreditHistoryServiceTest {

    @InjectMocks
    private CreditHistoryService creditHistoryService;

    @Mock
    private CreditBalanceService creditBalanceService;

    @Mock
    private CreditHistoryRepository creditHistoryRepository;

    @Test
    @DisplayName("크레딧 내역 조회 성공")
    void getCreditHistory_Success() {
        Long userId = 1L;
        User user = User.builder().build();

        CreditBalance balance = CreditBalance.builder()
                .user(user)
                .dailyFreeCredit(45)
                .dailyFreeLimit(100)
                .freeCredit(0)
                .paidCredit(1500)
                .build();

        CreditHistory history = CreditHistory.builder()
                .user(user)
                .eventType(CreditEventType.LLM_QUERY)
                .eventName("AI 응답")
                .description("이산수학 집합론 문제 풀이")
                .amount(15)
                .changeType(CreditChangeType.SPEND)
                .creditType(CreditType.DAILY)
                .balanceAfterDaily(45)
                .balanceAfterFree(0)
                .balanceAfterPaid(1500)
                .build();

        Page<CreditHistory> historyPage = new PageImpl<>(List.of(history));

        when(creditBalanceService.getOrCreateBalance(userId)).thenReturn(balance);
        when(creditHistoryRepository.findByUserIdWithFilters(
                eq(userId), any(), any(), any(), any(), any(Pageable.class)
        )).thenReturn(historyPage);

        when(creditHistoryRepository.calculatePeriodSummary(
                eq(userId), any(CreditChangeType.class), any(CreditChangeType.class),
                any(CreditChangeType.class), any(), any()
        )).thenReturn(new CreditHistoryRepository.CreditPeriodSummary() {
            @Override
            public Long getTotalEarned() { return 2100L; }
            @Override
            public Long getTotalSpent() { return 555L; }
            @Override
            public Long getTotalExpired() { return 350L; }
        });

        CreditHistoryResponse response = creditHistoryService.getCreditHistory(
                userId, 0, 20, "all", "all", null, null
        );

        assertThat(response).isNotNull();
        assertThat(response.getCreditSummary().getTotalAvailable()).isEqualTo(1545);
        assertThat(response.getHistory().getContent()).hasSize(1);
    }

    @Test
    @DisplayName("잘못된 날짜 형식으로 예외 발생")
    void getCreditHistory_InvalidDateFormat() {
        Long userId = 1L;

        assertThatThrownBy(() -> creditHistoryService.getCreditHistory(
                userId, 0, 20, "all", "all", "2026-13-01", null
        )).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("조회 기간 90일 초과 예외 발생")
    void getCreditHistory_ExceedsMaxQueryDays() {
        Long userId = 1L;
        String startDate = "2026-01-01";
        String endDate = "2026-05-01"; // 120일

        assertThatThrownBy(() -> creditHistoryService.getCreditHistory(
                userId, 0, 20, "all", "all", startDate, endDate
        )).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("시작일만 전달 시 예외 발생")
    void getCreditHistory_OnlyStartDate() {
        Long userId = 1L;

        assertThatThrownBy(() -> creditHistoryService.getCreditHistory(
                userId, 0, 20, "all", "all", "2026-01-01", null
        )).isInstanceOf(BusinessException.class)
          .hasMessageContaining("시작일과 종료일을 모두 입력");
    }

    @Test
    @DisplayName("종료일만 전달 시 예외 발생")
    void getCreditHistory_OnlyEndDate() {
        Long userId = 1L;

        assertThatThrownBy(() -> creditHistoryService.getCreditHistory(
                userId, 0, 20, "all", "all", null, "2026-01-31"
        )).isInstanceOf(BusinessException.class)
          .hasMessageContaining("시작일과 종료일을 모두 입력");
    }
}
