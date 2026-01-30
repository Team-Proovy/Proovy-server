package com.proovy.domain.conversation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "대화 생성 요청")
public class ConversationRequest {

    @Schema(description = "사용자 질문/지시문", example = "이 문제를 풀어줘", required = true)
    @NotBlank(message = "질문 내용은 필수입니다.")
    private String text;

    @Schema(description = "LaTeX 수식 입력", example = "\\int_{0}^{1} x^2 dx")
    private String latex;

    @Schema(description = "언급된 자산 ID 목록", example = "[1, 2, 3]")
    private List<Long> mentionedAssetIds;

    @Schema(description = "선택된 기능 목록", example = "[\"Solve\", \"Check\"]")
    private List<String> chosenFeatures;

    @Schema(description = "캔버스 이미지 ID 목록", example = "[10, 20]")
    private List<Long> canvasImageIds;
}
