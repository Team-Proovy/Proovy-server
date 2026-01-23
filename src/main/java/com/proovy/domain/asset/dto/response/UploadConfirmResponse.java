package com.proovy.domain.asset.dto.response;

import com.proovy.domain.asset.entity.Asset;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class UploadConfirmResponse {

    private Long assetId;
    private String fileName;
    private Long fileSize;
    private String mimeType;
    private String source;
    private String ocrStatus;
    private LocalDateTime createdAt;

    public static UploadConfirmResponse from(Asset asset, String ocrStatus) {
        return UploadConfirmResponse.builder()
                .assetId(asset.getId())
                .fileName(asset.getFileName())
                .fileSize(asset.getFileSize())
                .mimeType(asset.getMimeType())
                .source(asset.getSource().name())
                .ocrStatus(ocrStatus)
                .createdAt(asset.getCreatedAt())
                .build();
    }
}
