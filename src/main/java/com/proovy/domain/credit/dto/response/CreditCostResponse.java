package com.proovy.domain.credit.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

@Getter
@Builder
@AllArgsConstructor
@Schema(description = "크레딧 비용 조회 응답")
public class CreditCostResponse {

    @Schema(description = "비용 정책 목록")
    private List<CreditCostItemDto> costs;

    @Schema(description = "기능별 비용 목록")
    private List<FeatureCostDto> featureCosts;

    @Schema(description = "난이도별 배율")
    private Map<String, Double> difficultyMultipliers;

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

    @Getter
    @Builder
    @AllArgsConstructor
    @Schema(description = "기능별 크레딧 비용")
    public static class FeatureCostDto {

        @Schema(description = "기능 이름", example = "Solve")
        private String featureName;

        @Schema(description = "기본 비용", example = "10")
        private Integer baseCost;

        @Schema(description = "쉬운 난이도 비용", example = "10")
        private Integer easyCost;

        @Schema(description = "중간 난이도 비용", example = "15")
        private Integer mediumCost;

        @Schema(description = "어려운 난이도 비용", example = "20")
        private Integer hardCost;
    }
}
