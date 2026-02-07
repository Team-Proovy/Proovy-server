package com.proovy.domain.credit.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor
@Schema(description = "크레딧 잔액 조회 응답")
public class CreditBalanceResponse {

    @Schema(description = "일일 무료 크레딧 잔액")
    private Integer dailyFreeCredit;

    @Schema(description = "일일 무료 크레딧 한도")
    private Integer dailyFreeLimit;

    @Schema(description = "일일 크레딧 만료 시간")
    private LocalDateTime dailyExpiresAt;

    @Schema(description = "무료 크레딧 잔액")
    private Integer freeCredit;

    @Schema(description = "유료 크레딧 잔액")
    private Integer paidCredit;

    @Schema(description = "유료 크레딧 만료 시간")
    private LocalDateTime paidExpiresAt;

    @Schema(description = "총 사용 가능 크레딧")
    private Integer totalAvailable;

    @Schema(description = "지정된 비용을 사용할 수 있는지 여부")
    private Boolean canUse;

    @Schema(description = "확인한 비용 (canUse 확인용)")
    private Integer checkedCost;
}
