package com.proovy.domain.embedding;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;

import java.time.LocalDateTime;

@Entity
@Table(name = "note_embeddings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NoteEmbedding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "note_id", nullable = false)
    private Long noteId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "embedding_model", nullable = false, length = 50)
    private String embeddingModel;

    @Access(AccessType.FIELD)
    @Column(name = "vector", nullable = false, columnDefinition = "vector(1536)")
    @ColumnTransformer(read = "vector::text", write = "?::vector")
    private String vector;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Builder
    public NoteEmbedding(Long noteId, Long userId, String contentHash, String embeddingModel, String vector) {
        this.noteId = noteId;
        this.userId = userId;
        this.contentHash = contentHash;
        this.embeddingModel = (embeddingModel == null || embeddingModel.isBlank())
                ? "text-embedding-3-small" : embeddingModel;
        this.vector = vector;
    }

    public void updateVector(String vector, String contentHash) {
        this.vector = vector;
        this.contentHash = contentHash;
        this.updatedAt = LocalDateTime.now();
    }
}
