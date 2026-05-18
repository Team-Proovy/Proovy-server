package com.proovy.domain.asset.service;

import com.proovy.domain.asset.constant.AllowedMimeType;
import com.proovy.domain.asset.dto.request.UploadUrlRequest;
import com.proovy.domain.asset.dto.response.AssetDetailResponse;
import com.proovy.domain.asset.dto.response.DownloadUrlResponse;
import com.proovy.domain.asset.dto.response.UploadUrlResponse;
import com.proovy.domain.asset.entity.Asset;
import com.proovy.domain.asset.entity.AssetStatus;
import com.proovy.domain.asset.repository.AssetRepository;
import com.proovy.domain.note.entity.Note;
import com.proovy.domain.note.repository.NoteRepository;
import com.proovy.domain.user.entity.PlanType;
import com.proovy.domain.user.repository.UserPlanRepository;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.infra.gcs.GcsService;
import com.proovy.global.infra.thumbnail.ThumbnailService;
import com.proovy.global.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AssetsServiceImpl implements AssetsService {

    private final AssetRepository assetRepository;
    private final NoteRepository noteRepository;
    private final GcsService s3Service;
    private final UserPlanRepository userPlanRepository;
    private final ThumbnailService thumbnailService;

    private static final int PRESIGNED_URL_DURATION_MINUTES = 15;
    private static final long BYTES_PER_MB = 1024L * 1024L;
    private static final int OCR_TIMEOUT_MINUTES = 30; // OCR 처리 타임아웃
    private static final long NOTE_STORAGE_LIMIT = 536_870_912L; // 512MB
    private final ApplicationContext applicationContext;

    /**
     * Self-injection을 통해 트랜잭션 프록시를 가져옴
     */
    private AssetsService getSelf() {
        return applicationContext.getBean(AssetsService.class);
    }

    @Override
    @Transactional
    public UploadUrlResponse generateUploadUrl(Long userId, UploadUrlRequest request) {
        // 1. 파일 형식 검증 (PDF, PNG, JPEG만 허용)
        validateMimeType(request.getMimeType());

        // 2. 파일 크기 검증
        PlanType planType = userPlanRepository.findActivePlanTypeByUserId(userId)
                .orElse(PlanType.FREE);
        validateFileSize(request.getFileSize(), planType);

        // 3. 파일명 검증
        validateFileName(request.getFileName());

        // 4. 노트 존재 및 권한 검증
        Note note = noteRepository.findById(request.getNoteId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTE4041));

        if (!note.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOTE4031);
        }

        // 5. 스토리지 용량 검증
        validateStorageCapacity(request.getNoteId(), request.getFileSize());

        // 6. S3 Key 생성
        String s3Key = generateS3Key(userId, request.getNoteId(), request.getFileName());

        // 7. Asset 엔티티 생성 (PENDING 상태)
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(PRESIGNED_URL_DURATION_MINUTES);

        Asset asset = Asset.builder()
                .userId(userId)
                .noteId(request.getNoteId())
                .fileName(request.getFileName())
                .fileSize(request.getFileSize())
                .mimeType(request.getMimeType())
                .objectKey(s3Key)
                .source(Asset.AssetSource.upload)
                .status(AssetStatus.PENDING)
                .uploadExpiresAt(expiresAt)
                .build();

        Asset savedAsset = assetRepository.save(asset);

        // 8. Presigned URL 생성
        String presignedUrl = s3Service.generatePresignedUploadUrl(
                s3Key,
                request.getMimeType(),
                PRESIGNED_URL_DURATION_MINUTES
        );

        log.info("[Asset] Presigned URL 발급 완료 - assetId: {}, noteId: {}, userId: {}",
                savedAsset.getId(), request.getNoteId(), userId);

        return UploadUrlResponse.of(savedAsset.getId(), presignedUrl, expiresAt);
    }

    private void validateMimeType(String mimeType) {
        if (!AllowedMimeType.isAllowed(mimeType)) {
            throw new BusinessException(ErrorCode.ASSET4001);
        }
    }

    private void validateFileSize(Long fileSize, PlanType planType) {
        long maxFileSize = (long) planType.getSingleFileLimitMb() * BYTES_PER_MB;
        if (fileSize > maxFileSize) {
            throw new BusinessException(ErrorCode.ASSET4002);
        }
    }

    private static final int MAX_FILE_NAME_LENGTH = 255;

    private void validateFileName(String fileName) {
        if (fileName == null || fileName.trim().length() < 2 || fileName.trim().length() > MAX_FILE_NAME_LENGTH) {
            throw new BusinessException(ErrorCode.ASSET4005);
        }
    }

    private void validateStorageCapacity(Long noteId, Long fileSize) {
        Long uploadedSize = assetRepository.sumFileSizeByNoteIdAndStatus(noteId, AssetStatus.UPLOADED);
        Long pendingSize = assetRepository.sumFileSizeByNoteIdAndStatus(noteId, AssetStatus.PENDING);

        long currentUsage = (uploadedSize != null ? uploadedSize : 0L) + (pendingSize != null ? pendingSize : 0L);

        if (currentUsage + fileSize > NOTE_STORAGE_LIMIT) {
            throw new BusinessException(ErrorCode.STORAGE4005);
        }
    }

    private String generateS3Key(Long userId, Long noteId, String fileName) {
        String uuid = UUID.randomUUID().toString();
        return String.format("users/%d/notes/%d/assets/%s_%s",
                userId, noteId, uuid, fileName);
    }

    @Override
    public DownloadUrlResponse generateDownloadUrl(Long userId, Long assetId) {
        // 1. Asset 존재 확인
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ASSET4041));

        // 2. 권한 검증 (본인 소유 자산인지 확인)
        if (!asset.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.ASSET4031);
        }

        // 3. 업로드 완료 상태 검증
        if (asset.getStatus() != AssetStatus.UPLOADED) {
            throw new BusinessException(ErrorCode.ASSET4006);
        }

        // 3. Presigned URL 생성
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(PRESIGNED_URL_DURATION_MINUTES);
        String downloadUrl = s3Service.generatePresignedDownloadUrl(
                asset.getObjectKey(),
                asset.getFileName(),
                PRESIGNED_URL_DURATION_MINUTES
        );

        log.debug("[Asset] 다운로드 URL 발급 완료 - assetId: {}", assetId);

        return DownloadUrlResponse.of(asset.getId(), asset.getFileName(), downloadUrl, expiresAt);
    }

    @Override
    @Transactional
    public AssetDetailResponse confirmUpload(Long userId, Long assetId) {
        // 1. Asset 존재 확인
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ASSET4041));

        // 2. 권한 검증
        if (!asset.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.ASSET4031);
        }

        // 3. 이미 확인된 자산인지 확인 (중복 호출 방지)
        if (asset.getStatus() == AssetStatus.UPLOADED) {
            throw new BusinessException(ErrorCode.ASSET4091);
        }

        // 4. S3 파일 존재 여부 확인
        if (!s3Service.doesFileExist(asset.getObjectKey())) {
            throw new BusinessException(ErrorCode.ASSET4007);
        }

        // 5. Asset 상태 업데이트 (PENDING → UPLOADED, ocrStatus → processing)
        // Optimistic Locking으로 동시 요청 처리
        try {
            asset.markAsUploaded();
            assetRepository.saveAndFlush(asset);
        } catch (OptimisticLockingFailureException e) {
            // 동시 요청으로 인한 충돌 - 이미 다른 요청이 처리됨
            log.warn("[Asset] 업로드 확인 동시 요청 충돌 - assetId: {}", assetId);
            throw new BusinessException(ErrorCode.ASSET4091);
        }

        // 6. 썸네일 생성
        final Long savedAssetId = asset.getId();
        final String s3Key = asset.getObjectKey();
        final String mimeType = asset.getMimeType();

        // 이미지 파일인 경우: 동기적으로 썸네일 생성 (빠른 응답)
        if (mimeType.startsWith("image/")) {
            try {
                String thumbnailS3Key = thumbnailService.generateThumbnailSync(s3Key, mimeType);
                if (thumbnailS3Key == null) {
                    // WEBP 등 디코딩 이슈가 있어도 이미지는 즉시 미리보기가 가능해야 하므로 원본을 fallback으로 사용
                    thumbnailS3Key = s3Key;
                    log.warn("[Asset] 이미지 썸네일 생성 실패 - 원본 fallback 사용 - assetId: {}, mimeType: {}", savedAssetId, mimeType);
                }

                // 썸네일 생성 성공 또는 fallback - 같은 트랜잭션 내에서 직접 업데이트
                asset.updateThumbnail(thumbnailS3Key);
                assetRepository.save(asset);
                log.info("[Asset] 이미지 썸네일 생성 완료 (동기) - assetId: {}, thumbnailS3Key: {}", savedAssetId, thumbnailS3Key);
            } catch (Exception e) {
                log.error("[Asset] 이미지 썸네일 생성 중 오류 - assetId: {}, error: {}", savedAssetId, e.getMessage(), e);
            }
        }
        // PDF 파일인 경우: 비동기로 썸네일 생성 (시간 소요)
        else if (mimeType.equals("application/pdf")) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    thumbnailService.generateThumbnailAsync(savedAssetId, s3Key, mimeType);
                }
            });
        }

        log.info("[Asset] 업로드 확인 완료 - assetId: {}, userId: {}, ocrStatus: {}",
                assetId, userId, asset.getOcrStatus());

        // 썸네일 URL 생성 (있는 경우)
        String thumbnailUrl = asset.getThumbnailObjectKey() != null
                ? s3Service.getThumbnailUrl(asset.getThumbnailObjectKey())
                : null;

        return AssetDetailResponse.from(asset, thumbnailUrl);
    }

    @Override
    public AssetDetailResponse getAssetDetail(Long userId, Long assetId) {
        // 1. Asset 존재 확인
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ASSET4041));

        // 2. 권한 검증
        if (!asset.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.ASSET4031);
        }

        log.debug("[Asset] 자산 상세 조회 - assetId: {}, ocrStatus: {}", assetId, asset.getOcrStatus());

        // 썸네일 URL 생성 (있는 경우)
        String thumbnailUrl = asset.getThumbnailObjectKey() != null
                ? s3Service.getThumbnailUrl(asset.getThumbnailObjectKey())
                : null;

        return AssetDetailResponse.from(asset, thumbnailUrl);
    }

    @Override
    @Transactional
    public void deleteAsset(Long userId, Long assetId) {
        // 1. Asset 존재 확인
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ASSET4041));

        // 2. 권한 검증
        if (!asset.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.ASSET4031);
        }

        // GCS 키 저장 (트랜잭션 커밋 후 삭제를 위해)
        final String objectKey = asset.getObjectKey();
        final String thumbnailObjectKey = asset.getThumbnailObjectKey();

        // 3. DB Asset 레코드 삭제 (먼저 수행)
        assetRepository.delete(asset);

        // 4. 트랜잭션 커밋 후 GCS 파일 삭제 (afterCommit 콜백)
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // GCS 원본 파일 삭제
                try {
                    s3Service.deleteFile(objectKey);
                    log.info("[Asset] GCS 원본 파일 삭제 완료 - objectKey: {}", objectKey);
                } catch (Exception e) {
                    log.error("[Asset] GCS 원본 파일 삭제 실패 - objectKey: {}, error: {}", objectKey, e.getMessage());
                }

                // GCS 썸네일 삭제 (원본과 다른 경우에만)
                if (thumbnailObjectKey != null && !thumbnailObjectKey.equals(objectKey)) {
                    try {
                        s3Service.deleteFile(thumbnailObjectKey);
                        log.info("[Asset] GCS 썸네일 삭제 완료 - thumbnailObjectKey: {}", thumbnailObjectKey);
                    } catch (Exception e) {
                        log.error("[Asset] GCS 썸네일 삭제 실패 - thumbnailObjectKey: {}, error: {}", thumbnailObjectKey, e.getMessage());
                    }
                }
            }
        });

        log.info("[Asset] 자산 삭제 완료 (DB) - assetId: {}, userId: {}", assetId, userId);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markOcrFailed(Long assetId) {
        assetRepository.findById(assetId).ifPresent(asset -> {
            if (asset.getOcrStatus() == Asset.OcrStatus.processing) {
                asset.failOcr();
                assetRepository.save(asset);
                log.warn("[OCR] OCR 상태를 failed로 변경 - assetId: {}", assetId);
            }
        });
    }

    @Override
    @Transactional
    @Scheduled(fixedRate = 300000) // 5분마다 실행
    public void markTimedOutOcrAsFailed() {
        LocalDateTime timeoutThreshold = LocalDateTime.now().minusMinutes(OCR_TIMEOUT_MINUTES);

        List<Asset> timedOutAssets = assetRepository.findByOcrStatusAndUpdatedAtBefore(
                Asset.OcrStatus.processing,
                timeoutThreshold
        );

        if (!timedOutAssets.isEmpty()) {
            log.info("[OCR] 타임아웃된 OCR 처리 자산 발견 - count: {}", timedOutAssets.size());

            for (Asset asset : timedOutAssets) {
                asset.failOcr();
                assetRepository.save(asset);
                log.warn("[OCR] OCR 타임아웃으로 failed 처리 - assetId: {}, updatedAt: {}",
                        asset.getId(), asset.getUpdatedAt());
            }
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateAssetThumbnail(Long assetId, String thumbnailS3Key) {
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> {
                    log.warn("[Asset] Asset 미존재로 썸네일 연결 실패 - assetId: {}, thumbnailS3Key: {}",
                            assetId, thumbnailS3Key);
                    return new BusinessException(ErrorCode.ASSET4041);
                });

        asset.updateThumbnail(thumbnailS3Key);
        assetRepository.save(asset);
        log.info("[Asset] Asset 썸네일 업데이트 완료 - assetId: {}, thumbnailS3Key: {}",
                assetId, thumbnailS3Key);
    }
}
