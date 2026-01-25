package com.proovy.domain.asset.dto.response;

import com.proovy.domain.asset.entity.Asset;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
@Schema(description = "S3 업로드 완료 확인 응답")
public class UploadConfirmResponse {

    @Schema(description = "자산 ID", example = "1")
    private Long assetId;

    @Schema(description = "파일명", example = "discrete_math_HW2.pdf")
    private String fileName;

    @Schema(description = "파일 크기 (bytes)", example = "1048576")
    private Long fileSize;

    @Schema(description = "MIME 타입", example = "application/pdf")
    private String mimeType;

    @Schema(description = "파일 출처 (upload: 사용자 업로드)", example = "upload")
    private String source;

    @Schema(description = "OCR 처리 상태", example = "processing")
    private String ocrStatus;

    @Schema(description = "생성 시각", example = "2025-01-05T10:00:00")
    private LocalDateTime createdAt;

    public static UploadConfirmResponse from(Asset asset) {
        return UploadConfirmResponse.builder()
                .assetId(asset.getId())
                .fileName(asset.getFileName())
                .fileSize(asset.getFileSize())
                .mimeType(asset.getMimeType())
                .source(asset.getSource().name())
                .ocrStatus(asset.getOcrStatus() != null ? asset.getOcrStatus().name() : null)
                .createdAt(asset.getCreatedAt())
                .build();
    }
}
