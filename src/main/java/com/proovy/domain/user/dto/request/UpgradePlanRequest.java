package com.proovy.domain.user.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record UpgradePlanRequest(
        @NotBlank(message = "플랜 타입은 필수입니다.")
        @Schema(description = "변경할 플랜 타입 (free, standard, pro)", example = "standard", allowableValues = {"free", "standard", "pro"})
        @JsonProperty("planType")
        String planType
) {
}

