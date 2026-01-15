package com.proovy.domain.storage.dto.response;

import com.proovy.domain.asset.entity.Asset;
import com.proovy.domain.asset.entity.FileCategory;
import lombok.Builder;

@Builder
public record AssetSummaryDto(
        Long assetId,
        String fileName,
        String mimeType,
        String fileCategory,
        String source,
        String thumbnailUrl
) {
    public static AssetSummaryDto from(Asset asset, String thumbnailUrl) {
        FileCategory category = FileCategory.fromMimeType(asset.getMimeType());
        String source = asset.getSource() != null
                ? asset.getSource().name().toLowerCase()
                : "upload";

        return AssetSummaryDto.builder()
                .assetId(asset.getId())
                .fileName(asset.getFileName())
                .mimeType(asset.getMimeType())
                .fileCategory(category.getValue())
                .source(source)
                .thumbnailUrl(category.hasThumbnail() ? thumbnailUrl : null)
                .build();
    }
}
