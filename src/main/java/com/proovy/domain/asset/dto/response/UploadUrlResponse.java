package com.proovy.domain.asset.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class UploadUrlResponse {

    private Long assetId;
    private String uploadUrl;
    private LocalDateTime expiresAt;

    public static UploadUrlResponse of(Long assetId, String uploadUrl, LocalDateTime expiresAt) {
        return UploadUrlResponse.builder()
                .assetId(assetId)
                .uploadUrl(uploadUrl)
                .expiresAt(expiresAt)
                .build();
    }
}
