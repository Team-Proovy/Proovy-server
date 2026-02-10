package com.proovy.domain.conversation.dto.response;

import com.proovy.domain.asset.entity.Asset;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class CanvasImageUploadResponse {

    private Long assetId;
    private String source;
    private String fileName;
    private Long fileSize;
    private String mimeType;
    private String storageKey;
    private String uploadUrl;
    private LocalDateTime createdAt;

    public static CanvasImageUploadResponse of(Asset asset, String uploadUrl) {
        return CanvasImageUploadResponse.builder()
                .assetId(asset.getId())
                .source(asset.getSource().name())
                .fileName(asset.getFileName())
                .fileSize(asset.getFileSize())
                .mimeType(asset.getMimeType())
                .storageKey(asset.getS3Key())
                .uploadUrl(uploadUrl)
                .createdAt(asset.getCreatedAt())
                .build();
    }
}
