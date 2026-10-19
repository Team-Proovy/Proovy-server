package com.proovy.domain.run.entity;

import com.proovy.global.entity.AssignedIdEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * run 생성 시 고정한 Note Asset 의존성. 같은 Note의 asset만 가리키며(composite FK),
 * run이 PENDING(attempt 0)일 때만 insert할 수 있고 이후 수정·추가하지 않는다(DB trigger).
 */
@Entity
@Table(name = "run_asset_dependencies")
@IdClass(RunAssetDependency.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RunAssetDependency extends AssignedIdEntity<RunAssetDependency.Key> {

    @Id
    @Column(name = "run_id")
    private UUID runId;

    @Id
    @Column(name = "asset_id")
    private Long assetId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "dependency_kind", length = 30)
    private RunAssetDependencyKind dependencyKind;

    @Column(name = "note_id", nullable = false, updatable = false)
    private Long noteId;

    @Column(name = "content_version", nullable = false, updatable = false)
    private Integer contentVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public RunAssetDependency(UUID runId, Long noteId, Long assetId, RunAssetDependencyKind dependencyKind,
                              int contentVersion, Instant createdAt) {
        this.runId = Objects.requireNonNull(runId);
        this.noteId = Objects.requireNonNull(noteId);
        this.assetId = Objects.requireNonNull(assetId);
        this.dependencyKind = Objects.requireNonNull(dependencyKind);
        this.contentVersion = contentVersion;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    @Override
    public Key getId() {
        return new Key(runId, assetId, dependencyKind);
    }

    @EqualsAndHashCode
    @NoArgsConstructor
    public static class Key implements Serializable {
        private UUID runId;
        private Long assetId;
        private RunAssetDependencyKind dependencyKind;

        public Key(UUID runId, Long assetId, RunAssetDependencyKind dependencyKind) {
            this.runId = runId;
            this.assetId = assetId;
            this.dependencyKind = dependencyKind;
        }
    }
}
