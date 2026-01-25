package com.proovy.domain.asset.service;

import com.proovy.domain.asset.constant.AllowedMimeType;
import com.proovy.domain.asset.dto.request.UploadUrlRequest;
import com.proovy.domain.asset.dto.response.AssetDetailResponse;
import com.proovy.domain.asset.dto.response.DownloadUrlResponse;
import com.proovy.domain.asset.dto.response.UploadConfirmResponse;
import com.proovy.domain.asset.dto.response.UploadUrlResponse;
import com.proovy.domain.asset.entity.Asset;
import com.proovy.domain.asset.entity.AssetStatus;
import com.proovy.domain.asset.repository.AssetRepository;
import com.proovy.domain.note.entity.Note;
import com.proovy.domain.note.repository.NoteRepository;
import com.proovy.domain.user.entity.PlanType;
import com.proovy.domain.user.repository.UserPlanRepository;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.infra.s3.S3Service;
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
import org.springframework.web.reactive.function.client.WebClient;

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
    private final UserPlanRepository userPlanRepository;
    private final S3Service s3Service;
    private final UserPlanRepository userPlanRepository;

    private static final int PRESIGNED_URL_DURATION_MINUTES = 15;
    private static final long NOTE_STORAGE_LIMIT = 536_870_912L; // 512MB
    private static final long BYTES_PER_MB = 1024L * 1024L;
    private static final int OCR_TIMEOUT_MINUTES = 30; // OCR 처리 타임아웃
    private static final int PRESIGNED_URL_DURATION_MINUTES = 15;
  
    // TODO: 크레딧별 단일 파일 크기 제한이 있음. 수정해야함.
    private static final long MAX_FILE_SIZE = 31_457_280L; // 30MB

    private final WebClient webClient;
    private final ApplicationContext applicationContext;

    @Value("${proovy.ai.server-url:http://localhost:8081}")
    private String aiServerUrl;

    /**
     * Self-injection을 통해 트랜잭션 프록시를 가져옴
     */
    private AssetsService getSelf() {
        return applicationContext.getBean(AssetsService.class);
    }

    private static final int PRESIGNED_URL_DURATION_MINUTES = 15;
    private static final long NOTE_STORAGE_LIMIT = 512L * 1024 * 1024; // 512MB (노트당 제한)
    private static final int OCR_TIMEOUT_MINUTES = 30; // OCR 처리 타임아웃

    @Override
    @Transactional
    public UploadUrlResponse generateUploadUrl(Long userId, UploadUrlRequest request) {
        // 1. 파일 형식 검증 (PDF, PNG, JPEG만 허용)
        validateMimeType(request.getMimeType());

        // 2. 사용자 플랜 조회
        PlanType planType = userPlanRepository.findActivePlanTypeByUserId(userId)
                .orElse(PlanType.FREE);

        // 3. 파일 크기 검증 (플랜별 제한 적용)
        validateFileSize(request.getFileSize(), planType);

        // 4. 파일명 검증
        validateFileName(request.getFileName());

        // 5. 노트 존재 및 권한 검증
        Note note = noteRepository.findById(request.getNoteId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTE4041));

        if (!note.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOTE4031);
        }

        // 6. 스토리지 용량 검증
        validateStorageCapacity(request.getNoteId(), request.getFileSize());

        // 7. S3 Key 생성
        String s3Key = generateS3Key(userId, request.getNoteId(), request.getFileName());

        // 8. Asset 엔티티 생성 (PENDING 상태)
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(PRESIGNED_URL_DURATION_MINUTES);

        Asset asset = Asset.builder()
                .userId(userId)
                .noteId(request.getNoteId())
                .fileName(request.getFileName())
                .fileSize(request.getFileSize())
                .mimeType(request.getMimeType())
                .s3Key(s3Key)
                .source(Asset.AssetSource.upload)
                .status(AssetStatus.PENDING)
                .uploadExpiresAt(expiresAt)
                .build();

        Asset savedAsset = assetRepository.save(asset);

        // 9. Presigned URL 생성
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
        if (fileSize > planType.getMaxFileSizeBytes()) {
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

        // 3. 업로드 완료 상태 검증!
        if (asset.getStatus() != AssetStatus.UPLOADED) {
            throw new BusinessException(ErrorCode.ASSET4006);
        }

        // 3. Presigned URL 생성
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(PRESIGNED_URL_DURATION_MINUTES);
        String downloadUrl = s3Service.generatePresignedDownloadUrl(
                asset.getS3Key(),
                asset.getFileName(),
                PRESIGNED_URL_DURATION_MINUTES
        );

        log.debug("[Asset] 다운로드 URL 발급 완료 - assetId: {}", assetId);

        return DownloadUrlResponse.of(asset.getId(), asset.getFileName(), downloadUrl, expiresAt);
    }

    @Override
    @Transactional
    public UploadConfirmResponse confirmUpload(Long userId, Long assetId) {
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
        if (!s3Service.doesFileExist(asset.getS3Key())) {
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

        // 6. OCR 처리 요청 (트랜잭션 커밋 후 비동기 실행)
        // OCR 요청에 필요한 정보 저장 (트랜잭션 외부에서 사용)
        final Long savedAssetId = asset.getId();
        final String s3Key = asset.getS3Key();
        final String mimeType = asset.getMimeType();

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                requestOcrProcessingAsync(savedAssetId, s3Key, mimeType);
            }
        });

        log.info("[Asset] 업로드 확인 완료 - assetId: {}, userId: {}", assetId, userId);

        return UploadConfirmResponse.from(asset);
    }

    /**
     * AI 서버에 OCR 처리 요청 (비동기)
     * 트랜잭션 커밋 후 afterCommit 콜백에서 호출됨
     * 요청 실패 시 ocrStatus를 failed로 변경
     *
     * @param assetId  자산 ID
     * @param s3Key    S3 저장 경로
     * @param mimeType MIME 타입
     */
    private void requestOcrProcessingAsync(Long assetId, String s3Key, String mimeType) {
        try {
            log.info("[OCR] OCR 처리 요청 시작 - assetId: {}, s3Key: {}", assetId, s3Key);

            webClient.post()
                    .uri(aiServerUrl + "/api/ocr/process")
                    .bodyValue(java.util.Map.of(
                            "assetId", assetId,
                            "s3Key", s3Key,
                            "mimeType", mimeType
                    ))
                    .retrieve()
                    .bodyToMono(Void.class)
                    .subscribe(
                            result -> log.info("[OCR] OCR 처리 요청 완료 - assetId: {}", assetId),
                            error -> {
                                log.error("[OCR] OCR 처리 요청 실패 - assetId: {}, error: {}", assetId, error.getMessage());
                                // 별도 트랜잭션에서 OCR 실패 처리
                                try {
                                    getSelf().markOcrFailed(assetId);
                                } catch (Exception e) {
                                    log.error("[OCR] OCR 실패 상태 변경 중 오류 - assetId: {}, error: {}", assetId, e.getMessage());
                                }
                            }
                    );
        } catch (Exception e) {
            log.error("[OCR] OCR 처리 요청 예외 - assetId: {}, error: {}", assetId, e.getMessage());
            // 동기 예외 발생 시에도 실패 처리
            try {
                getSelf().markOcrFailed(assetId);
            } catch (Exception ex) {
                log.error("[OCR] OCR 실패 상태 변경 중 오류 - assetId: {}, error: {}", assetId, ex.getMessage());
            }
        }
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

        return AssetDetailResponse.from(asset);
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

        // S3 키 저장 (트랜잭션 커밋 후 삭제를 위해)
        final String s3Key = asset.getS3Key();
        final String thumbnailS3Key = asset.getThumbnailS3Key();

        // 3. DB Asset 레코드 삭제 (먼저 수행)
        assetRepository.delete(asset);

        // 4. 트랜잭션 커밋 후 S3 파일 삭제 (afterCommit 콜백)
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // S3 원본 파일 삭제
                try {
                    s3Service.deleteFile(s3Key);
                    log.info("[Asset] S3 원본 파일 삭제 완료 - s3Key: {}", s3Key);
                } catch (Exception e) {
                    // S3 삭제 실패해도 DB는 이미 커밋됨 (로깅만 수행)
                    log.error("[Asset] S3 원본 파일 삭제 실패 - s3Key: {}, error: {}", s3Key, e.getMessage());
                }

                // S3 썸네일 삭제 (있는 경우)
                if (thumbnailS3Key != null) {
                    try {
                        s3Service.deleteFile(thumbnailS3Key);
                        log.info("[Asset] S3 썸네일 삭제 완료 - s3Key: {}", thumbnailS3Key);
                    } catch (Exception e) {
                        log.error("[Asset] S3 썸네일 삭제 실패 - s3Key: {}, error: {}", thumbnailS3Key, e.getMessage());
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
}
