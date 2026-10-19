package com.proovy.domain.conversation.entity;

import com.proovy.global.entity.AssignedIdEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * source user message의 직접 입력 첨부 (Turn Attachment). Note 삭제 시 함께 정리되고
 * 후속 message나 Note Corpus로 복제하지 않는다. 검증된 media metadata만 저장하며 insert-only다.
 * 실제 업로드·검증 경로는 S09에서 구현한다.
 */
@Entity
@Immutable
@Table(name = "turn_attachments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TurnAttachment extends AssignedIdEntity<UUID> {

    @Id
    @Column(name = "attachment_id")
    private UUID id;

    @Column(name = "note_id", nullable = false)
    private Long noteId;

    @Column(name = "source_message_id", nullable = false)
    private UUID sourceMessageId;

    @Column(name = "attachment_index", nullable = false)
    private Integer attachmentIndex;

    @Column(name = "object_key", nullable = false, length = 500)
    private String objectKey;

    @Column(name = "thumbnail_object_key", length = 500)
    private String thumbnailObjectKey;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "byte_size", nullable = false)
    private Long byteSize;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "pixel_width")
    private Integer pixelWidth;

    @Column(name = "pixel_height")
    private Integer pixelHeight;

    @Column(name = "pixel_count")
    private Long pixelCount;

    @Column(name = "upload_policy_version", nullable = false, length = 64)
    private String uploadPolicyVersion;

    @Column(name = "page_understanding_version", nullable = false, length = 64)
    private String pageUnderstandingVersion;

    @Column(name = "image_normalization_version", nullable = false, length = 64)
    private String imageNormalizationVersion;

    @Column(name = "display_label", length = 200)
    private String displayLabel;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Builder
    private TurnAttachment(UUID id, Long noteId, UUID sourceMessageId, int attachmentIndex, String objectKey,
                           String thumbnailObjectKey, String sha256, String mimeType, long byteSize,
                           Integer pageCount, Integer pixelWidth, Integer pixelHeight, Long pixelCount,
                           String uploadPolicyVersion, String pageUnderstandingVersion,
                           String imageNormalizationVersion, String displayLabel, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.noteId = Objects.requireNonNull(noteId);
        this.sourceMessageId = Objects.requireNonNull(sourceMessageId);
        this.attachmentIndex = attachmentIndex;
        this.objectKey = Objects.requireNonNull(objectKey);
        this.thumbnailObjectKey = thumbnailObjectKey;
        this.sha256 = Objects.requireNonNull(sha256);
        this.mimeType = Objects.requireNonNull(mimeType);
        this.byteSize = byteSize;
        this.pageCount = pageCount;
        this.pixelWidth = pixelWidth;
        this.pixelHeight = pixelHeight;
        this.pixelCount = pixelCount;
        this.uploadPolicyVersion = Objects.requireNonNull(uploadPolicyVersion);
        this.pageUnderstandingVersion = Objects.requireNonNull(pageUnderstandingVersion);
        this.imageNormalizationVersion = Objects.requireNonNull(imageNormalizationVersion);
        this.displayLabel = displayLabel;
        this.createdAt = Objects.requireNonNull(createdAt);
    }
}
