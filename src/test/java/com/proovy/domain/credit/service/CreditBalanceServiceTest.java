package com.proovy.domain.credit.service;

import com.proovy.domain.credit.entity.CreditBalance;
import com.proovy.domain.credit.entity.CreditHistory;
import com.proovy.domain.credit.repository.CreditBalanceRepository;
import com.proovy.domain.credit.repository.CreditHistoryRepository;
import com.proovy.domain.user.entity.PlanType;
import com.proovy.domain.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class CreditBalanceServiceTest {

    @InjectMocks
    private CreditBalanceService creditBalanceService;

    @Mock
    private CreditBalanceRepository creditBalanceRepository;

    @Mock
    private CreditBalanceCreator creditBalanceCreator;

    @Mock
    private CreditHistoryRepository creditHistoryRepository;

    @Test
    @DisplayName("같은 userPlanId로 월간 크레딧 재지급 요청 시 중복 지급하지 않는다")
    void skipDuplicateMonthlyGrantForSamePlan() {
        Long userId = 1L;
        Long userPlanId = 101L;
        User user = User.builder().build();

        CreditBalance balance = CreditBalance.builder()
                .user(user)
                .paidCredit(2000)
                .lastMonthlyGrantPlanId(userPlanId)
                .build();

        given(creditBalanceRepository.findByUserIdForUpdate(userId)).willReturn(Optional.of(balance));

        creditBalanceService.grantMonthlyCredit(userId, PlanType.STANDARD, userPlanId, 2000);

        assertThat(balance.getPaidCredit()).isEqualTo(2000);
        then(creditBalanceRepository).should(never()).save(any(CreditBalance.class));
        then(creditHistoryRepository).should(never()).save(any(CreditHistory.class));
    }

    @Test
    @DisplayName("월간 크레딧 지급 시 잔액/만료일/지급 참조키를 갱신하고 히스토리를 남긴다")
    void grantMonthlyCreditUpdatesBalanceAndHistory() {
        Long userId = 1L;
        Long userPlanId = 202L;
        User user = User.builder().build();

        CreditBalance balance = CreditBalance.builder()
                .user(user)
                .dailyFreeCredit(10)
                .dailyFreeLimit(50)
                .freeCredit(0)
                .paidCredit(0)
                .build();

        given(creditBalanceRepository.findByUserIdForUpdate(userId)).willReturn(Optional.of(balance));

        creditBalanceService.grantMonthlyCredit(userId, PlanType.PRO, userPlanId, 5000);

        assertThat(balance.getPaidCredit()).isEqualTo(5000);
        assertThat(balance.getDailyFreeLimit()).isEqualTo(100);
        assertThat(balance.getDailyFreeCredit()).isEqualTo(100);
        assertThat(balance.getLastMonthlyGrantPlanId()).isEqualTo(userPlanId);
        assertThat(balance.getPaidExpiresAt()).isAfter(LocalDateTime.now().plusDays(20));
        assertThat(balance.getPaidExpiresAt()).isBefore(LocalDateTime.now().plusMonths(2));

        then(creditBalanceRepository).should().save(balance);

        ArgumentCaptor<CreditHistory> historyCaptor = ArgumentCaptor.forClass(CreditHistory.class);
        then(creditHistoryRepository).should().save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getAmount()).isEqualTo(5000);
    }
}
