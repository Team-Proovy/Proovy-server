package com.proovy.domain.asset.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proovy.domain.asset.entity.Asset;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "자산 상세 정보 + OCR 결과 응답")
public class AssetDetailResponse {

    @Schema(description = "자산 ID", example = "1")
    private Long assetId;

    @Schema(description = "소속 노트 ID", example = "10")
    private Long noteId;

    @Schema(description = "파일 출처 (upload: 업로드, ai_generated: AI 생성)", example = "upload")
    private String source;

    @Schema(description = "파일명", example = "discrete_math_HW2.pdf")
    private String fileName;

    @Schema(description = "파일 크기 (bytes)", example = "1048576")
    private Long fileSize;

    @Schema(description = "MIME 타입", example = "application/pdf")
    private String mimeType;

    @Schema(description = "총 페이지 수 (PDF/PPT인 경우)", example = "12")
    private Integer totalPages;

    @Schema(description = "OCR 처리 상태 (pending, processing, completed, failed)", example = "completed")
    private String ocrStatus;

    @Schema(description = "OCR 추출 텍스트 (ocrStatus가 completed일 때만 포함)")
    private OcrTextDto ocrText;

    @Schema(description = "OCR 처리 완료 시각", example = "2025-01-05T10:01:00")
    private LocalDateTime ocrProcessedAt;

    @Schema(description = "생성 시각", example = "2025-01-05T10:00:00")
    private LocalDateTime createdAt;

    @Getter
    @Builder
    @Schema(description = "OCR 텍스트 정보")
    public static class OcrTextDto {
        @Schema(description = "페이지별 텍스트 배열")
        private java.util.List<PageText> pages;

        @Schema(description = "전체 텍스트")
        private String fullText;

        @Schema(description = "사용된 OCR 모델명", example = "PaddleOCR-VL")
        private String model;
    }

    @Getter
    @Builder
    @Schema(description = "페이지별 텍스트")
    public static class PageText {
        @Schema(description = "페이지 번호", example = "1")
        private Integer page;

        @Schema(description = "해당 페이지 텍스트")
        private String text;
    }

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static AssetDetailResponse from(Asset asset) {
        AssetDetailResponseBuilder builder = AssetDetailResponse.builder()
                .assetId(asset.getId())
                .noteId(asset.getNoteId())
                .source(asset.getSource().name())
                .fileName(asset.getFileName())
                .fileSize(asset.getFileSize())
                .mimeType(asset.getMimeType())
                .totalPages(asset.getTotalPages())
                .ocrStatus(asset.getOcrStatus() != null ? asset.getOcrStatus().name() : null)
                .ocrProcessedAt(asset.getOcrProcessedAt())
                .createdAt(asset.getCreatedAt());

        // OCR 완료 시에만 ocrText 파싱
        if (asset.getOcrStatus() == Asset.OcrStatus.completed && asset.getOcrText() != null) {
            try {
                OcrTextDto ocrTextDto = objectMapper.readValue(asset.getOcrText(), OcrTextDto.class);
                builder.ocrText(ocrTextDto);
            } catch (JsonProcessingException e) {
                // 파싱 실패 시 무시
            }
        }

        return builder.build();
    }
}
