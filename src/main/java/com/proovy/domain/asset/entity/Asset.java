package com.proovy.domain.asset.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "assets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Asset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long noteId;

    @Column(nullable = false, length = 255)
    private String fileName;

    @Column(nullable = false)
    private Long fileSize; // bytes 단위

    @Column(nullable = false, length = 100)
    private String mimeType;

    @Column(name = "object_key", nullable = false, length = 500)
    private String objectKey; // GCS 저장 경로

    @Column(name = "staging_object_key", length = 500)
    private String stagingObjectKey; // 업로드 직후 staging 경로 (만료 시점까지 잔여 객체 정리 추적)

    @Column(name = "staging_generation")
    private Long stagingGeneration; // 읽고 검증한 staging GCS 세대(generation)

    @Column(name = "pending_confirmed_object_key", length = 500)
    private String pendingConfirmedObjectKey; // 승격 copy 전 기록하는 확정 경로 intent (롤백·경쟁 정리)

    @Column(name = "thumbnail_object_key", length = 500)
    private String thumbnailObjectKey; // 썸네일 GCS 경로

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private AssetSource source; // upload (Phase 1은 생성 파일 저장 없음)

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private AssetStatus status; // PENDING, ACTIVE, FAILED, DELETING, DELETED

    private LocalDateTime uploadExpiresAt; // Presigned URL 만료 시간

    @Column(name = "content_version", nullable = false)
    private Integer contentVersion = 1; // run dependency·Source Reference가 고정하는 content version

    @Column(length = 64)
    private String sha256; // 확정된 원본의 SHA-256 (S09에서 기록)

    @Column(name = "page_count")
    private Integer pageCount; // PDF 총 페이지 수

    @Column(name = "pixel_width")
    private Integer pixelWidth; // 이미지 원본 가로 픽셀

    @Column(name = "pixel_height")
    private Integer pixelHeight; // 이미지 원본 세로 픽셀

    @Column(name = "pixel_count")
    private Long pixelCount; // 이미지 원본 픽셀 수 (pixel_width * pixel_height)

    @Column(length = 20)
    @Enumerated(EnumType.STRING)
    private OcrStatus ocrStatus; // OCR 처리 상태

    @Column(columnDefinition = "TEXT")
    private String ocrText; // OCR 결과 (JSON 형태)

    private LocalDateTime ocrProcessedAt; // OCR 처리 완료 시각

    // 삭제 operation (DELETING부터 새 run scope·source availability에서 제외, S13에서 사용)
    @Column(name = "deletion_operation_id")
    private UUID deletionOperationId;

    @Column(name = "deletion_requested_at")
    private Instant deletionRequestedAt;

    @Column(name = "brain_cleanup_acked_at")
    private Instant brainCleanupAckedAt;

    @Column(name = "storage_cleaned_at")
    private Instant storageCleanedAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Version
    private Long version;

    @Builder
    public Asset(Long userId, Long noteId, String fileName, Long fileSize,
                 String mimeType, String objectKey, String thumbnailObjectKey, AssetSource source,
                 AssetStatus status, LocalDateTime uploadExpiresAt) {
        this.userId = userId;
        this.noteId = noteId;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.mimeType = mimeType;
        this.objectKey = objectKey;
        this.thumbnailObjectKey = thumbnailObjectKey;
        this.source = source;
        this.status = status;
        this.uploadExpiresAt = uploadExpiresAt;
    }

    public enum AssetSource {
        upload
    }

    public enum OcrStatus {
        pending,
        processing,
        completed,
        failed
    }

    /**
     * 업로드 확정 (PENDING → ACTIVE)
     */
    public void markAsUploaded() {
        this.status = AssetStatus.ACTIVE;
        this.ocrStatus = OcrStatus.processing;
    }

    /**
     * 바이트 검증 결과(SHA-256, 실제 크기, PDF 페이지 수 또는 이미지 픽셀 크기)와 함께 업로드 확정.
     * OCR/썸네일 후처리 수명주기는 {@link #markAsUploaded()}와 동일하게 processing으로 유지한다.
     */
    public void confirmMetadata(String sha256, long actualFileSize, Integer pageCount,
                                Integer pixelWidth, Integer pixelHeight, Long pixelCount) {
        this.status = AssetStatus.ACTIVE;
        this.fileSize = actualFileSize;
        this.sha256 = sha256;
        this.pageCount = pageCount;
        this.pixelWidth = pixelWidth;
        this.pixelHeight = pixelHeight;
        this.pixelCount = pixelCount;
        this.ocrStatus = OcrStatus.processing;
        this.pendingConfirmedObjectKey = null;
    }

    /**
     * 확정 copy 전 durable promotion intent와 staging 정보를 기록한다.
     */
    public void claimPromotionIntent(String confirmedObjectKey, String stagingKey, Long stagingGeneration) {
        this.pendingConfirmedObjectKey = confirmedObjectKey;
        this.stagingObjectKey = stagingKey;
        this.stagingGeneration = stagingGeneration;
    }

    public void clearPendingConfirmedObjectKey() {
        this.pendingConfirmedObjectKey = null;
    }

    public void clearStagingGeneration() {
        this.stagingGeneration = null;
    }

    public void clearStagingCleanupTracking() {
        this.stagingObjectKey = null;
        this.stagingGeneration = null;
    }

    /**
     * 검증된 업로드 바이트를 클라이언트 PUT 경로와 분리된 불변 확정 경로로 승격할 때 objectKey를 갱신한다.
     */
    public void relocateObjectKey(String confirmedObjectKey) {
        if (confirmedObjectKey != null && !confirmedObjectKey.isBlank()) {
            this.objectKey = confirmedObjectKey;
        }
    }

    /**
     * 검증 실패·정책 거부 업로드 정리 대상으로 상태 전이 (GCS 객체 삭제 재시도 추적용)
     */
    public void markUploadFailed() {
        this.status = AssetStatus.FAILED;
    }

    public void markUploadFailed(String stagingKey, Long stagingGeneration) {
        this.status = AssetStatus.FAILED;
        this.stagingObjectKey = stagingKey;
        this.stagingGeneration = stagingGeneration;
        this.pendingConfirmedObjectKey = null;
    }

    /**
     * OCR 처리 완료
     */
    public void completeOcr(String ocrText, Integer pageCount) {
        this.ocrStatus = OcrStatus.completed;
        this.ocrText = ocrText;
        this.pageCount = pageCount;
        this.ocrProcessedAt = LocalDateTime.now();
    }

    /**
     * OCR 처리 실패
     */
    public void failOcr() {
        this.ocrStatus = OcrStatus.failed;
    }

    /**
     * 썸네일 Object 키 업데이트
     */
    public void updateThumbnail(String thumbnailObjectKey) {
        this.thumbnailObjectKey = thumbnailObjectKey;
        // 이미지·PDF 썸네일 생성 완료 시 대기 중이던 processing 상태를 완료로 전이하되,
        // ocrProcessedAt은 실제 OCR 완료(completeOcr) 시점에만 기록한다.
        if (this.ocrStatus == OcrStatus.processing) {
            this.ocrStatus = OcrStatus.completed;
        }
    }
}
