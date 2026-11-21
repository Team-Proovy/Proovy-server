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
import com.proovy.domain.run.admission.RunAssetDeletionGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

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
    private final RunAssetDeletionGuard runAssetDeletionGuard;
    private final AssetContentValidator assetContentValidator;
    private final PlatformTransactionManager transactionManager;

    private static final int PRESIGNED_URL_DURATION_MINUTES = 15;
    private static final long BYTES_PER_MB = 1024L * 1024L;
    private static final int OCR_TIMEOUT_MINUTES = 30; // OCR 처리 타임아웃
    private static final long NOTE_STORAGE_LIMIT = 536_870_912L; // 512MB
    private final ApplicationContext applicationContext;
    private final ConcurrentHashMap<Long, String> inFlightPromotions = new ConcurrentHashMap<>();

    @PersistenceContext
    private EntityManager entityManager;

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
        Long uploadedSize = assetRepository.sumFileSizeByNoteIdAndStatus(noteId, AssetStatus.ACTIVE);
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
        if (asset.getStatus() != AssetStatus.ACTIVE) {
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
        if (asset.getStatus() == AssetStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ASSET4091);
        }
        if (asset.getStatus() != AssetStatus.PENDING) {
            throw new BusinessException(ErrorCode.ASSET4006);
        }

        // 4. S3/GCS 메타데이터·바이트 스트림 상한(플랜 vs 포맷 최솟값)·시그니처·SHA-256 검증
        //    검증/정책 거부 시에만 PENDING→FAILED 전환 후 객체를 정리하고, 일시적 GCS 오류는 재시도 가능 상태로 보존한다.
        final String stagingS3Key = asset.getObjectKey();
        final String mimeType = asset.getMimeType();
        PlanType planType = userPlanRepository.findActivePlanTypeByUserId(userId)
                .orElse(PlanType.FREE);
        long planMaxBytes = (long) planType.getSingleFileLimitMb() * BYTES_PER_MB;
        long formatMaxBytes = "application/pdf".equals(mimeType)
                ? AssetContentValidator.PDF_MAX_BYTES
                : AssetContentValidator.IMAGE_MAX_BYTES;
        long effectiveMaxBytes = Math.min(planMaxBytes, formatMaxBytes);

        GcsService.PinnedBlobBytes pinned = null;
        byte[] verifiedBytes;
        AssetContentValidator.ValidatedAssetMetadata validated;
        try {
            pinned = s3Service.readFileBytesBoundedPinned(stagingS3Key, effectiveMaxBytes, ErrorCode.ASSET4002);
            verifiedBytes = pinned != null ? pinned.bytes() : null;
            validated = assetContentValidator.validate(verifiedBytes, mimeType);
            validateFileSize(validated.byteSize(), planType);
            if (validated.byteSize() > asset.getFileSize()) {
                validateStorageCapacity(asset.getNoteId(), validated.byteSize() - asset.getFileSize());
            }
        } catch (BusinessException e) {
            if (isValidationOrPolicyRejection(e.getErrorCode())) {
                cleanupRejectedUpload(asset.getId(), stagingS3Key, pinned != null ? pinned.generation() : null);
            }
            throw e;
        }

        // 4.5. 검증 완료된 바이트를 불변 확정 경로로 승격하기 전에 REQUIRES_NEW로 durable intent를 선점한다.
        //      롤백·단절 또는 서로 다른 generation 경쟁 시 고아 confirmed 객체가 남지 않도록 한다.
        final String confirmedS3Key = buildConfirmedS3Key(stagingS3Key, validated.sha256());
        final Long stagingGeneration = pinned != null ? pinned.generation() : null;
        final Long assetIdRef = asset.getId();
        final boolean txSyncActive = TransactionSynchronizationManager.isSynchronizationActive();

        executeRequiresNew(() -> claimPendingPromotionIntent(
                assetIdRef, confirmedS3Key, stagingS3Key, stagingGeneration));
        if (entityManager != null && entityManager.contains(asset)) {
            entityManager.refresh(asset);
        }

        inFlightPromotions.put(assetIdRef, confirmedS3Key);
        try {
            if (txSyncActive) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        inFlightPromotions.remove(assetIdRef, confirmedS3Key);
                        if (status != TransactionSynchronization.STATUS_COMMITTED) {
                            reconcileRolledBackPromotion(assetIdRef, confirmedS3Key);
                        }
                    }
                });
            }

            if (!confirmedS3Key.equals(stagingS3Key)) {
                try (ByteArrayInputStream verifiedStream = new ByteArrayInputStream(verifiedBytes)) {
                    s3Service.uploadFile(confirmedS3Key, verifiedStream, verifiedBytes.length, mimeType);
                } catch (BusinessException e) {
                    throw e;
                } catch (Exception e) {
                    throw new BusinessException(ErrorCode.COMMON500);
                }
            }

            // 5. Asset 상태 업데이트 (PENDING → ACTIVE, 불변 key·SHA-256·메타데이터 확정)
            // Optimistic Locking으로 동시 요청 처리
            try {
                asset.relocateObjectKey(confirmedS3Key);
                asset.confirmMetadata(
                        validated.sha256(),
                        validated.byteSize(),
                        validated.pageCount(),
                        validated.pixelWidth(),
                        validated.pixelHeight(),
                        validated.pixelCount()
                );
                assetRepository.saveAndFlush(asset);
            } catch (OptimisticLockingFailureException e) {
                // 동시 요청으로 인한 충돌 - 이미 다른 요청이 처리됨
                log.warn("[Asset] 업로드 확인 동시 요청 충돌 - assetId: {}", assetId);
                throw new BusinessException(ErrorCode.ASSET4091);
            }

            // 6. 썸네일 생성 및 스테이징 객체 정리
            final Long savedAssetId = asset.getId();

            // 이미지 파일인 경우: 이미 검증·고정된 verifiedBytes로 동기 썸네일 생성 (mutable key 재조회 차단)
            if (mimeType.startsWith("image/")) {
                try {
                    String thumbnailS3Key = thumbnailService.generateThumbnailSync(verifiedBytes, confirmedS3Key, mimeType);
                    if (thumbnailS3Key == null) {
                        // 디코딩 이슈가 있어도 이미지는 즉시 미리보기가 가능해야 하므로 불변 확정 원본을 fallback으로 사용
                        thumbnailS3Key = confirmedS3Key;
                        log.warn("[Asset] 이미지 썸네일 생성 실패 - 확정 원본 fallback 사용 - assetId: {}, mimeType: {}",
                                savedAssetId, mimeType);
                    }

                    // 썸네일 생성 성공 또는 fallback - 같은 트랜잭션 내에서 직접 업데이트
                    asset.updateThumbnail(thumbnailS3Key);
                    assetRepository.save(asset);
                    log.info("[Asset] 이미지 썸네일 생성 완료 (동기) - assetId: {}, thumbnailS3Key: {}",
                            savedAssetId, thumbnailS3Key);
                } catch (Exception e) {
                    log.error("[Asset] 이미지 썸네일 생성 중 오류 - assetId: {}, error: {}", savedAssetId, e.getMessage(), e);
                }
            }

            final boolean hasSeparateStaging = !confirmedS3Key.equals(stagingS3Key);
            final boolean asyncPdfThumb = mimeType.equals("application/pdf");
            if (txSyncActive) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        if (hasSeparateStaging) {
                            attemptActiveStagingCleanup(savedAssetId, stagingS3Key, stagingGeneration);
                        }
                        if (asyncPdfThumb) {
                            thumbnailService.generateThumbnailAsync(savedAssetId, confirmedS3Key, mimeType);
                        }
                    }
                });
            } else if (hasSeparateStaging) {
                attemptActiveStagingCleanup(savedAssetId, stagingS3Key, stagingGeneration);
            }
        } finally {
            if (!txSyncActive) {
                inFlightPromotions.remove(assetIdRef, confirmedS3Key);
            }
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

        // run이 의존한 asset은 hard delete할 수 없다 (S13 DELETING 전환 전, 접수와 같은 Note 잠금 순서)
        runAssetDeletionGuard.requireNoRunDependency(List.of(asset.getNoteId()), List.of(assetId));

        // GCS 키 저장 (트랜잭션 커밋 후 삭제를 위해)
        final String objectKey = asset.getObjectKey();
        final String thumbnailObjectKey = asset.getThumbnailObjectKey();
        final String stagingObjectKey = asset.getStagingObjectKey();
        final String pendingConfirmedObjectKey = asset.getPendingConfirmedObjectKey();

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

                if (stagingObjectKey != null && !stagingObjectKey.equals(objectKey)) {
                    try {
                        s3Service.deleteFile(stagingObjectKey);
                    } catch (Exception e) {
                        log.warn("[Asset] GCS staging 파일 삭제 실패 - stagingObjectKey: {}, error: {}",
                                stagingObjectKey, e.getMessage());
                    }
                }

                if (pendingConfirmedObjectKey != null && !pendingConfirmedObjectKey.equals(objectKey)) {
                    try {
                        s3Service.deleteFile(pendingConfirmedObjectKey);
                    } catch (Exception e) {
                        log.warn("[Asset] GCS pending confirmed 파일 삭제 실패 - key: {}, error: {}",
                                pendingConfirmedObjectKey, e.getMessage());
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

    private static boolean isValidationOrPolicyRejection(ErrorCode errorCode) {
        return errorCode == ErrorCode.ASSET4001
                || errorCode == ErrorCode.ASSET4002
                || errorCode == ErrorCode.STORAGE4002
                || errorCode == ErrorCode.STORAGE4005;
    }

    static String buildConfirmedS3Key(String stagingS3Key, String sha256) {
        String prefix = (sha256 != null && sha256.length() >= 16) ? sha256.substring(0, 16) : "verified";
        int lastSlash = stagingS3Key.lastIndexOf('/');
        String dir = lastSlash >= 0 ? stagingS3Key.substring(0, lastSlash) : "";
        String leaf = lastSlash >= 0 ? stagingS3Key.substring(lastSlash + 1) : stagingS3Key;
        int sep = leaf.indexOf('_');
        String confirmedLeaf = sep > 0
                ? leaf.substring(0, sep) + "-" + prefix + leaf.substring(sep)
                : prefix + "_" + leaf;
        String confirmedDir = dir.endsWith("/assets")
                ? dir.substring(0, dir.length() - "/assets".length()) + "/confirmed"
                : (dir.isEmpty() ? "confirmed" : dir + "/confirmed");
        String candidate = confirmedDir + "/" + confirmedLeaf;
        if (candidate.length() > 500) {
            int maxLeaf = Math.max(16, 500 - confirmedDir.length() - 1);
            confirmedLeaf = confirmedLeaf.substring(0, maxLeaf);
            candidate = confirmedDir + "/" + confirmedLeaf;
        }
        return candidate;
    }

    private boolean claimPendingPromotionIntent(
            Long assetId, String confirmedKey, String stagingKey, Long stagingGeneration) {
        try {
            Asset pending = assetRepository.findById(assetId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.ASSET4091));
            if (pending.getStatus() != AssetStatus.PENDING) {
                throw new BusinessException(ErrorCode.ASSET4091);
            }
            if (pending.getPendingConfirmedObjectKey() != null
                    && !pending.getPendingConfirmedObjectKey().equals(confirmedKey)) {
                throw new BusinessException(ErrorCode.ASSET4091);
            }
            pending.claimPromotionIntent(confirmedKey, stagingKey, stagingGeneration);
            assetRepository.saveAndFlush(pending);
            return true;
        } catch (OptimisticLockingFailureException e) {
            throw new BusinessException(ErrorCode.ASSET4091);
        }
    }

    private void reconcileRolledBackPromotion(Long assetId, String confirmedKey) {
        assetRepository.findById(assetId).ifPresent(asset -> {
            String trackedKey = asset.getPendingConfirmedObjectKey();
            if (trackedKey == null || !trackedKey.equals(confirmedKey)) {
                return;
            }
            if (asset.getStatus() == AssetStatus.ACTIVE && trackedKey.equals(asset.getObjectKey())) {
                executeRequiresNew(() -> clearPendingConfirmedKey(assetId, trackedKey));
                return;
            }
            try {
                s3Service.deleteFile(trackedKey);
                executeRequiresNew(() -> clearPendingConfirmedKey(assetId, trackedKey));
            } catch (Exception e) {
                log.warn("[Asset] 롤백된 confirmed GCS 객체 정리 실패 (스케줄러로 이월) - assetId: {}, key: {}, error: {}",
                        assetId, trackedKey, e.getMessage());
            }
        });
    }

    private void attemptActiveStagingCleanup(Long assetId, String stagingKey, Long generation) {
        boolean generationCleared = generation == null;
        if (generation != null) {
            try {
                s3Service.deleteFile(stagingKey, generation);
                generationCleared = true;
                executeRequiresNew(() -> clearStagingGeneration(assetId, generation));
            } catch (Exception e) {
                log.warn("[Asset] 확정 후 staging generation 정리 실패 - assetId: {}, key: {}, gen: {}, error: {}",
                        assetId, stagingKey, generation, e.getMessage());
            }
        }
        if (!generationCleared) {
            return;
        }
        Boolean expiredActive = executeRequiresNew(() -> isActiveAssetWriteWindowExpired(assetId));
        if (!Boolean.TRUE.equals(expiredActive)) {
            return;
        }
        try {
            if (generation != null) {
                s3Service.deleteFile(stagingKey);
            } else {
                deleteObjectWithOptionalGeneration(stagingKey, null);
            }
            executeRequiresNew(() -> clearStagingTracking(assetId, stagingKey));
        } catch (Exception e) {
            log.warn("[Asset] 만료 staging 키 정리 실패 - assetId: {}, key: {}, error: {}",
                    assetId, stagingKey, e.getMessage());
        }
    }

    private void cleanupRejectedUpload(Long assetId, String s3Key, Long generation) {
        boolean markedFailed = false;
        try {
            markedFailed = Boolean.TRUE.equals(executeRequiresNew(
                    () -> markPendingAssetFailed(assetId, s3Key, generation)));
        } catch (Exception e) {
            log.warn("[Asset] 거부된 업로드 FAILED 전이 실패 - assetId: {}, error: {}", assetId, e.getMessage());
        }
        if (!markedFailed) {
            return;
        }
        boolean deletedGeneration = false;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                deleteObjectWithOptionalGeneration(s3Key, generation);
                deletedGeneration = true;
                break;
            } catch (Exception e) {
                log.warn("[Asset] 거부된 업로드 GCS 정리 실패 (시도 {}/2) - assetId: {}, s3Key: {}, generation: {}, error: {}",
                        attempt, assetId, s3Key, generation, e.getMessage());
            }
        }
        if (!deletedGeneration) {
            return;
        }
        if (generation != null) {
            try {
                executeRequiresNew(() -> clearStagingGeneration(assetId, generation));
            } catch (Exception e) {
                log.warn("[Asset] FAILED staging generation 초기화 실패 - assetId: {}, error: {}",
                        assetId, e.getMessage());
            }
        }
        Boolean expiredFailed = executeRequiresNew(() -> isFailedAssetWriteWindowExpired(assetId));
        if (!Boolean.TRUE.equals(expiredFailed)) {
            return;
        }
        try {
            if (generation != null) {
                s3Service.deleteFile(s3Key);
            }
            executeRequiresNew(() -> deleteFailedAssetById(assetId));
        } catch (Exception e) {
            log.warn("[Asset] 만료 FAILED 자산 최종 정리 실패 - assetId: {}, error: {}", assetId, e.getMessage());
        }
    }

    @Override
    @Scheduled(fixedRate = 300000)
    public void cleanupFailedUploadAssets() {
        reconcilePendingPromotions();
        reconcileFailedAssets();
        reconcileActiveStagingCleanups();
    }

    private void reconcilePendingPromotions() {
        List<Asset> pendingPromotions = assetRepository.findByPendingConfirmedObjectKeyIsNotNull();
        for (Asset asset : pendingPromotions) {
            String pendingKey = asset.getPendingConfirmedObjectKey();
            if (pendingKey == null || inFlightPromotions.containsKey(asset.getId())) {
                continue;
            }
            if (asset.getStatus() == AssetStatus.ACTIVE && pendingKey.equals(asset.getObjectKey())) {
                try {
                    executeRequiresNew(() -> clearPendingConfirmedKey(asset.getId(), pendingKey));
                } catch (Exception e) {
                    log.warn("[Asset] 확정된 pending intent 해제 실패 - assetId: {}, error: {}",
                            asset.getId(), e.getMessage());
                }
                continue;
            }
            try {
                s3Service.deleteFile(pendingKey);
                executeRequiresNew(() -> clearPendingConfirmedKey(asset.getId(), pendingKey));
            } catch (Exception e) {
                log.warn("[Asset] 고아 confirmed 객체 정리 실패 - assetId: {}, key: {}, error: {}",
                        asset.getId(), pendingKey, e.getMessage());
            }
        }
    }

    private void reconcileFailedAssets() {
        List<Asset> failedAssets = assetRepository.findByStatus(AssetStatus.FAILED);
        for (Asset failed : failedAssets) {
            String key = failed.getStagingObjectKey() != null ? failed.getStagingObjectKey() : failed.getObjectKey();
            Long generation = failed.getStagingGeneration();
            try {
                if (generation != null) {
                    s3Service.deleteFile(key, generation);
                    executeRequiresNew(() -> clearStagingGeneration(failed.getId(), generation));
                }
                if (isUploadWriteWindowExpired(failed)) {
                    s3Service.deleteFile(key);
                    executeRequiresNew(() -> deleteFailedAssetById(failed.getId()));
                    log.info("[Asset] FAILED 업로드 GCS/DB 지연 정리 완료 - assetId: {}", failed.getId());
                }
            } catch (Exception e) {
                log.warn("[Asset] FAILED 업로드 GCS 지연 정리 재시도 실패 - assetId: {}, error: {}",
                        failed.getId(), e.getMessage());
            }
        }
    }

    private void reconcileActiveStagingCleanups() {
        List<Asset> stagingAssets = assetRepository.findByStagingObjectKeyIsNotNull();
        for (Asset asset : stagingAssets) {
            if (asset.getStatus() != AssetStatus.ACTIVE) {
                continue;
            }
            String key = asset.getStagingObjectKey();
            Long generation = asset.getStagingGeneration();
            if (key == null) {
                continue;
            }
            try {
                if (generation != null) {
                    s3Service.deleteFile(key, generation);
                    executeRequiresNew(() -> clearStagingGeneration(asset.getId(), generation));
                }
                if (isUploadWriteWindowExpired(asset)) {
                    s3Service.deleteFile(key);
                    executeRequiresNew(() -> clearStagingTracking(asset.getId(), key));
                    log.info("[Asset] ACTIVE 만료 staging GCS 정리 완료 - assetId: {}", asset.getId());
                }
            } catch (Exception e) {
                log.warn("[Asset] ACTIVE staging GCS 정리 재시도 실패 - assetId: {}, error: {}",
                        asset.getId(), e.getMessage());
            }
        }
    }

    private static boolean isUploadWriteWindowExpired(Asset asset) {
        LocalDateTime expiresAt = asset.getUploadExpiresAt();
        return expiresAt == null || !expiresAt.isAfter(LocalDateTime.now());
    }

    private void deleteObjectWithOptionalGeneration(String s3Key, Long generation) {
        if (generation != null) {
            s3Service.deleteFile(s3Key, generation);
        } else {
            s3Service.deleteFile(s3Key);
        }
    }

    private <T> T executeRequiresNew(Supplier<T> work) {
        if (transactionManager != null) {
            TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
            requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return requiresNew.execute(status -> work.get());
        }
        return work.get();
    }

    private boolean markPendingAssetFailed(Long assetId, String stagingKey, Long stagingGeneration) {
        return assetRepository.findById(assetId)
                .filter(pending -> pending.getStatus() == AssetStatus.PENDING)
                .map(pending -> {
                    pending.markUploadFailed(stagingKey, stagingGeneration);
                    assetRepository.saveAndFlush(pending);
                    return true;
                })
                .orElse(false);
    }

    private boolean clearPendingConfirmedKey(Long assetId, String expectedKey) {
        return assetRepository.findById(assetId)
                .filter(asset -> expectedKey.equals(asset.getPendingConfirmedObjectKey()))
                .filter(asset -> !(asset.getStatus() == AssetStatus.ACTIVE && expectedKey.equals(asset.getObjectKey())))
                .map(asset -> {
                    asset.clearPendingConfirmedObjectKey();
                    assetRepository.saveAndFlush(asset);
                    return true;
                })
                .orElse(false);
    }

    private boolean clearStagingGeneration(Long assetId, Long expectedGeneration) {
        return assetRepository.findById(assetId)
                .filter(asset -> expectedGeneration.equals(asset.getStagingGeneration()))
                .map(asset -> {
                    asset.clearStagingGeneration();
                    assetRepository.saveAndFlush(asset);
                    return true;
                })
                .orElse(false);
    }

    private boolean clearStagingTracking(Long assetId, String expectedKey) {
        return assetRepository.findById(assetId)
                .filter(asset -> expectedKey.equals(asset.getStagingObjectKey()))
                .map(asset -> {
                    asset.clearStagingCleanupTracking();
                    assetRepository.saveAndFlush(asset);
                    return true;
                })
                .orElse(false);
    }

    private boolean isActiveAssetWriteWindowExpired(Long assetId) {
        return assetRepository.findById(assetId)
                .filter(AssetsServiceImpl::isUploadWriteWindowExpired)
                .isPresent();
    }

    private boolean isFailedAssetWriteWindowExpired(Long assetId) {
        return assetRepository.findById(assetId)
                .filter(failed -> failed.getStatus() == AssetStatus.FAILED)
                .map(AssetsServiceImpl::isUploadWriteWindowExpired)
                .orElse(false);
    }

    private boolean deleteFailedAssetById(Long assetId) {
        return assetRepository.findById(assetId)
                .filter(failed -> failed.getStatus() == AssetStatus.FAILED)
                .map(failed -> {
                    assetRepository.delete(failed);
                    assetRepository.flush();
                    return true;
                })
                .orElse(false);
    }
}
