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

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public Asset(Long userId, Long noteId, String fileName, Long fileSize,
                 String mimeType, String s3Key, String thumbnailS3Key, AssetSource source) {
        this.userId = userId;
        this.noteId = noteId;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.mimeType = mimeType;
        this.s3Key = s3Key;
        this.thumbnailS3Key = thumbnailS3Key;
        this.source = source;
    }

    public enum AssetSource {
        upload,
        ai_generated
    }
}
