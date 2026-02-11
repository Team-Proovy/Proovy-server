package com.proovy.domain.storage.dto.response;

import com.proovy.domain.asset.entity.Asset;
import com.proovy.domain.asset.entity.FileCategory;
import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record AssetSummaryDto(
        Long assetId,
        String fileName,
        Long fileSize,
        String mimeType,
        String fileCategory,
        String source,
        String ocrStatus,
        String thumbnailUrl,
        LocalDateTime createdAt
) {
    public static AssetSummaryDto of(Asset asset, String thumbnailUrl) {
        FileCategory category = FileCategory.fromMimeType(asset.getMimeType());
        String source = asset.getSource() != null
                ? asset.getSource().name().toLowerCase()
                : "upload";

        String ocrStatus = asset.getOcrStatus() != null
                ? asset.getOcrStatus().name().toLowerCase()
                : "pending";

        return AssetSummaryDto.builder()
                .assetId(asset.getId())
                .fileName(asset.getFileName())
                .fileSize(asset.getFileSize())
                .mimeType(asset.getMimeType())
                .fileCategory(category.getValue())
                .source(source)
                .ocrStatus(ocrStatus)
                .thumbnailUrl(thumbnailUrl)
                .createdAt(asset.getCreatedAt())
                .build();
    }
}
