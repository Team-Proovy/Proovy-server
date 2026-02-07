package com.proovy.domain.credit.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "크레딧 사용 요청")
public class CreditUseRequest {

    @NotBlank
    @Schema(description = "이벤트 타입", example = "LLM_QUERY")
    private String eventType;

    @Schema(description = "문제 난이도 (easy, medium, hard)", example = "medium")
    private String difficulty;

    @Schema(description = "기능 이름", example = "Solve")
    private String featureName;

    @Schema(description = "설명", example = "이차방정식 문제 풀이")
    private String description;

    @Schema(description = "직접 지정할 크레딧 양 (null이면 자동 계산)", example = "10")
    private Integer amount;
}
