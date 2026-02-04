package com.proovy.domain.note.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
@Schema(description = "노트 자산 목록 응답")
public class AssetListResponse {

    @Schema(description = "자산 목록")
    private List<AssetInfo> assets;

    @Schema(description = "총 자산 수", example = "3")
    private Integer totalCount;

    @Getter
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "자산 정보")
    public static class AssetInfo {

        @Schema(description = "자산 ID", example = "1")
        private Long assetId;

        @Schema(description = "파일명", example = "Exogenous and endogenous variables.pdf")
        private String fileName;

        @Schema(description = "파일 크기 (bytes)", example = "1048576")
        private Long fileSize;

        @Schema(description = "MIME 타입", example = "application/pdf")
        private String mimeType;

        @Schema(description = "파일 유형 (PDF, DOCX, IMAGE, CODE 등)", example = "PDF")
        private String fileType;

        @Schema(description = "파일 출처 (UPLOAD: 사용자 업로드, GENERATED: AI 생성)", example = "UPLOAD")
        private String source;

        @Schema(description = "OCR 처리 상태 (PENDING, PROCESSING, COMPLETED, FAILED)", example = "COMPLETED")
        private String ocrStatus;

        @Schema(description = "썸네일 URL (없으면 null)", example = "https://s3.amazonaws.com/proovy/thumbnails/asset_1.png")
        private String thumbnailUrl;

        @Schema(description = "생성 시각", example = "2025-01-05T10:00:00")
        private LocalDateTime createdAt;
    }
}

