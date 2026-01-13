package com.proovy.domain.storage.service;

import com.proovy.domain.asset.entity.Asset;
import com.proovy.domain.asset.repository.AssetRepository;
import com.proovy.domain.storage.dto.request.BulkDeleteRequest;
import com.proovy.domain.storage.dto.response.BulkDeleteResponse;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.infra.s3.S3Service;
import com.proovy.global.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StorageService {

    private final AssetRepository assetRepository;
    private final S3Service s3Service;

    /**
     * 자산 일괄 삭제
     * TODO: JWT 인증 구현 후 @AuthenticationPrincipal로 userId 추출
     *
     * @param userId 사용자 ID (현재는 파라미터로 전달, 추후 JWT에서 추출)
     * @param request 삭제할 자산 ID 목록
     * @return 삭제된 자산 정보
     */
    @Transactional
    public BulkDeleteResponse bulkDeleteAssets(Long userId, BulkDeleteRequest request) {
        List<Long> assetIds = request.assetIds();

        // 자산 존재 여부 확인
        List<Asset> assets = assetRepository.findAllByIdIn(assetIds);
        if (assets.size() != assetIds.size()) {
            throw new BusinessException(ErrorCode.STORAGE4001);
        }

        // 권한 검증 (본인 소유 자산만 삭제 가능)
        long ownedCount = assetRepository.countByIdInAndUserId(assetIds, userId);
        if (ownedCount != assetIds.size()) {
            throw new BusinessException(ErrorCode.STORAGE4031);
        }

        // S3 키 수집 (원본 + 썸네일)
        List<String> s3KeysToDelete = new ArrayList<>();
        long totalFileSize = 0L;

        for (Asset asset : assets) {
            // 원본 파일
            s3KeysToDelete.add(asset.getS3Key());
            totalFileSize += asset.getFileSize();

            // 썸네일 파일
            if (asset.getThumbnailS3Key() != null) {
                s3KeysToDelete.add(asset.getThumbnailS3Key());
            }
        }

        // DB에서 자산 삭제
        assetRepository.deleteAllInBatch(assets);

        // S3에서 파일 삭제
        s3Service.deleteFiles(s3KeysToDelete);

        // 스토리지 용량 반환 로깅
        log.info("[Storage] 사용자 {} - {} 개 파일 삭제, 용량 반환: {} bytes",
                userId, assets.size(), totalFileSize);

        return BulkDeleteResponse.of(assetIds);
    }
}
