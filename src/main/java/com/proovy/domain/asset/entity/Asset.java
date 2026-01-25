package com.proovy.domain.asset.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

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

    @Column(nullable = false, length = 500)
    private String s3Key; // S3 저장 경로

    @Column(length = 500)
    private String thumbnailS3Key; // 썸네일 S3 경로

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private AssetSource source; // upload, ai_generated

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private AssetStatus status; // PENDING, UPLOADED, FAILED

    private LocalDateTime uploadExpiresAt; // Presigned URL 만료 시간

    private Integer totalPages; // 총 페이지 수 (PDF/PPT)

    @Column(length = 20)
    @Enumerated(EnumType.STRING)
    private OcrStatus ocrStatus; // OCR 처리 상태

    @Column(columnDefinition = "TEXT")
    private String ocrText; // OCR 결과 (JSON 형태)

    private LocalDateTime ocrProcessedAt; // OCR 처리 완료 시각

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
                 String mimeType, String s3Key, String thumbnailS3Key, AssetSource source,
                 AssetStatus status, LocalDateTime uploadExpiresAt) {
        this.userId = userId;
        this.noteId = noteId;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.mimeType = mimeType;
        this.s3Key = s3Key;
        this.thumbnailS3Key = thumbnailS3Key;
        this.source = source;
        this.status = status;
        this.uploadExpiresAt = uploadExpiresAt;
    }

    public enum AssetSource {
        upload,
        ai_generated
    }

    public enum OcrStatus {
        pending,
        processing,
        completed,
        failed
    }

    /**
     * 업로드 완료 상태로 변경
     */
    public void markAsUploaded() {
        this.status = AssetStatus.UPLOADED;
        this.ocrStatus = OcrStatus.processing;
    }

    /**
     * OCR 처리 완료
     */
    public void completeOcr(String ocrText, Integer totalPages) {
        this.ocrStatus = OcrStatus.completed;
        this.ocrText = ocrText;
        this.totalPages = totalPages;
        this.ocrProcessedAt = LocalDateTime.now();
    }

    /**
     * OCR 처리 실패
     */
    public void failOcr() {
        this.ocrStatus = OcrStatus.failed;
    }
}
