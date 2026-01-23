package com.proovy.domain.asset.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
@Schema(description = "다운로드 URL 응답")
public class DownloadUrlResponse {

    @Schema(description = "자산 ID", example = "1")
    private final Long assetId;

    @Schema(description = "파일명", example = "discrete_math_HW2.pdf")
    private final String fileName;

    @Schema(description = "다운로드용 Presigned URL")
    private final String downloadUrl;

    @Schema(description = "URL 만료 시각 (UTC)", example = "2025-01-05T10:15:00")
    private final LocalDateTime expiresAt;

    public static DownloadUrlResponse of(Long assetId, String fileName, String downloadUrl, LocalDateTime expiresAt) {
        return DownloadUrlResponse.builder()
                .assetId(assetId)
                .fileName(fileName)
                .downloadUrl(downloadUrl)
                .expiresAt(expiresAt)
                .build();
    }
}
