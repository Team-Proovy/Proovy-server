package com.proovy.domain.credit.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
@Schema(description = "크레딧 사용 응답")
public class CreditUseResponse {

    @Schema(description = "차감 성공 여부")
    private Boolean success;

    @Schema(description = "실제 차감된 크레딧 양")
    private Integer usedAmount;

    @Schema(description = "크레딧 잔액 정보")
    private BalanceDto balance;

    @Schema(description = "크레딧 부족 여부 (true면 잔액 부족)")
    private Boolean insufficientCredit;

    @Schema(description = "메시지")
    private String message;

    @Getter
    @Builder
    @AllArgsConstructor
    @Schema(description = "잔액 정보")
    public static class BalanceDto {

        @Schema(description = "일일 무료 크레딧 잔액")
        private Integer dailyFreeCredit;

        @Schema(description = "무료 크레딧 잔액")
        private Integer freeCredit;

        @Schema(description = "유료 크레딧 잔액")
        private Integer paidCredit;

        @Schema(description = "총 사용 가능 크레딧")
        private Integer totalAvailable;
    }
}
