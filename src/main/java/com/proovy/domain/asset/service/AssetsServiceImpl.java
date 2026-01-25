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
import com.proovy.global.exception.BusinessException;
import com.proovy.global.infra.s3.S3Service;
import com.proovy.global.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AssetsServiceImpl implements AssetsService {

    private final AssetRepository assetRepository;
    private final NoteRepository noteRepository;
    private final S3Service s3Service;
    private final WebClient webClient;

    @Value("${proovy.ai.server-url:http://localhost:8081}")
    private String aiServerUrl;

    private static final int PRESIGNED_URL_DURATION_MINUTES = 15;
    private static final long MAX_FILE_SIZE = 31_457_280L; // 30MB
    private static final long NOTE_STORAGE_LIMIT = 524_288_000L; // 500MB

    @Override
    @Transactional
    public UploadUrlResponse generateUploadUrl(Long userId, UploadUrlRequest request) {
        // 1. 파일 형식 검증 (PDF, PNG, JPEG만 허용)
        validateMimeType(request.getMimeType());

        // 2. 파일 크기 검증
        validateFileSize(request.getFileSize());

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
                .s3Key(s3Key)
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

    private void validateFileSize(Long fileSize) {
        if (fileSize > MAX_FILE_SIZE) {
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
        asset.markAsUploaded();
        assetRepository.save(asset);

        // 6. OCR 처리 요청 (비동기)
        requestOcrProcessing(asset);

        log.info("[Asset] 업로드 확인 완료 - assetId: {}, userId: {}", assetId, userId);

        return UploadConfirmResponse.from(asset);
    }

    /**
     * AI 서버에 OCR 처리 요청 (비동기)
     */
    private void requestOcrProcessing(Asset asset) {
        try {
            log.info("[OCR] OCR 처리 요청 시작 - assetId: {}, s3Key: {}", asset.getId(), asset.getS3Key());

            webClient.post()
                    .uri(aiServerUrl + "/api/ocr/process")
                    .bodyValue(java.util.Map.of(
                            "assetId", asset.getId(),
                            "s3Key", asset.getS3Key(),
                            "mimeType", asset.getMimeType()
                    ))
                    .retrieve()
                    .bodyToMono(Void.class)
                    .subscribe(
                            result -> log.info("[OCR] OCR 처리 요청 완료 - assetId: {}", asset.getId()),
                            error -> log.error("[OCR] OCR 처리 요청 실패 - assetId: {}, error: {}", asset.getId(), error.getMessage())
                    );
        } catch (Exception e) {
            // OCR 요청 실패해도 메인 API는 정상 응답 (비동기 처리)
            log.error("[OCR] OCR 처리 요청 예외 - assetId: {}, error: {}", asset.getId(), e.getMessage());
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

        // 3. S3 원본 파일 삭제
        s3Service.deleteFile(asset.getS3Key());

        // 4. S3 썸네일 삭제 (있는 경우)
        if (asset.getThumbnailS3Key() != null) {
            s3Service.deleteFile(asset.getThumbnailS3Key());
        }

        // 5. DB Asset 레코드 삭제
        assetRepository.delete(asset);

        log.info("[Asset] 자산 삭제 완료 - assetId: {}, userId: {}", assetId, userId);
    }
}
