package com.proovy.domain.credit.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
@AllArgsConstructor
@Schema(description = "크레딧 비용 조회 응답")
public class CreditCostResponse {

    @Schema(description = "비용 정책 목록")
    private List<CreditCostItemDto> costs;

    @Getter
    @Builder
    @AllArgsConstructor
    @Schema(description = "크레딧 비용 항목")
    public static class CreditCostItemDto {

        @Schema(description = "이벤트 타입", example = "OCR")
        private String eventType;

        @Schema(description = "기능 설명", example = "OCR 텍스트 추출")
        private String description;

        @Schema(description = "비용 (null인 경우 사용량 기반)", example = "10")
        private Integer costAmount;

        @Schema(description = "고정 비용 여부", example = "true")
        private Boolean isFixed;
    }
}
