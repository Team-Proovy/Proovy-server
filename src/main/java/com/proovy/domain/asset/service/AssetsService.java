package com.proovy.domain.asset.service;

import com.proovy.domain.asset.dto.request.UploadUrlRequest;
import com.proovy.domain.asset.dto.response.UploadUrlResponse;

public interface AssetsService {

    /**
     * S3 업로드용 Presigned URL 발급
     * @param userId 사용자 ID
     * @param request 업로드 요청 정보
     * @return Presigned URL 및 Asset 정보
     */
    UploadUrlResponse generateUploadUrl(Long userId, UploadUrlRequest request);
}
