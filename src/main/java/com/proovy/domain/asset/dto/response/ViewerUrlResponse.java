package com.proovy.domain.asset.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class ViewerUrlResponse {

    private Long assetId;
    private String fileName;
    private String viewerUrl;
    private Integer currentPage;
    private Integer totalPages;
    private LocalDateTime expiresAt;

    public static ViewerUrlResponse of(Long assetId, String fileName, String viewerUrl,
                                       Integer currentPage, Integer totalPages, LocalDateTime expiresAt) {
        return ViewerUrlResponse.builder()
                .assetId(assetId)
                .fileName(fileName)
                .viewerUrl(viewerUrl)
                .currentPage(currentPage)
                .totalPages(totalPages)
                .expiresAt(expiresAt)
                .build();
    }
}
