package com.proovy.domain.credit.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class CreditHistoryResponse {
    private CreditSummaryDto creditSummary;
    private HistoryPageDto history;
    private PeriodSummaryDto periodSummary;

    @Getter
    @Builder
    public static class CreditSummaryDto {
        private DailyFreeCreditDto dailyFreeCredit;
        private FreeCreditDto freeCredit;
        private PaidCreditDto paidCredit;
        private Integer totalAvailable;
    }

    @Getter
    @Builder
    public static class DailyFreeCreditDto {
        private Integer balance;
        private Integer limit;

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        private LocalDateTime expiresAt;
    }

    @Getter
    @Builder
    public static class FreeCreditDto {
        private Integer balance;
    }

    @Getter
    @Builder
    public static class PaidCreditDto {
        private Integer balance;

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        private LocalDateTime expiresAt;
    }

    @Getter
    @Builder
    public static class HistoryPageDto {
        private List<CreditHistoryItemDto> content;
        private PageInfoDto pageInfo;
    }

    @Getter
    @Builder
    public static class CreditHistoryItemDto {
        private Long historyId;
        private String eventType;
        private String eventName;
        private String description;
        private Integer amount;
        private String changeType;
        private String creditType;
        private BalanceAfterDto balanceAfter;

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        private LocalDateTime createdAt;
    }

    @Getter
    @Builder
    public static class BalanceAfterDto {
        private Integer daily;
        private Integer free;
        private Integer paid;
    }

    @Getter
    @Builder
    public static class PageInfoDto {
        private Integer page;
        private Integer size;
        private Long totalElements;
        private Integer totalPages;
        private Boolean hasNext;
    }

    @Getter
    @Builder
    public static class PeriodSummaryDto {
        private Long totalEarned;
        private Long totalSpent;
        private Long totalExpired;
        private String periodStart;
        private String periodEnd;
    }
}
