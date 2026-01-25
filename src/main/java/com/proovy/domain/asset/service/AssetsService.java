package com.proovy.domain.asset.service;

import com.proovy.domain.asset.dto.request.UploadUrlRequest;
import com.proovy.domain.asset.dto.response.AssetDetailResponse;
import com.proovy.domain.asset.dto.response.DownloadUrlResponse;
import com.proovy.domain.asset.dto.response.UploadConfirmResponse;
import com.proovy.domain.asset.dto.response.UploadUrlResponse;

public interface AssetsService {

    /**
     * S3 업로드용 Presigned URL 발급
     * @param userId 사용자 ID
     * @param request 업로드 요청 정보
     * @return Presigned URL 및 Asset 정보
     */
    UploadUrlResponse generateUploadUrl(Long userId, UploadUrlRequest request);

    /**
     * S3 다운로드용 Presigned URL 발급
     * @param userId 사용자 ID
     * @param assetId 자산 ID
     * @return 다운로드 URL 및 Asset 정보
     */
    DownloadUrlResponse generateDownloadUrl(Long userId, Long assetId);

    /**
     * S3 업로드 완료 확인 및 OCR 처리 시작
     * @param userId 사용자 ID
     * @param assetId 자산 ID
     * @return 업로드 확인 결과
     */
    UploadConfirmResponse confirmUpload(Long userId, Long assetId);

    /**
     * 자산 상세 정보 + OCR 결과 조회
     * @param userId 사용자 ID
     * @param assetId 자산 ID
     * @return 자산 상세 정보
     */
    AssetDetailResponse getAssetDetail(Long userId, Long assetId);

    /**
     * 자산 삭제
     * @param userId 사용자 ID
     * @param assetId 자산 ID
     */
    void deleteAsset(Long userId, Long assetId);
}
