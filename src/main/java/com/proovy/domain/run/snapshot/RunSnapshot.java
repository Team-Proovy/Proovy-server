package com.proovy.domain.run.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.proovy.domain.run.entity.AiRun;
import com.proovy.domain.run.entity.RunAssetDependency;
import com.proovy.domain.run.entity.RunAssetDependencyKind;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 접수 시점에 고정한 실행 입력 snapshot. {@link RunSnapshotFactory}가 만들고 접수 transaction(1.79)이
 * {@link #toPendingRun}·{@link #assetDependencies}로 저장한다. 저장 뒤에는 {@link RunSnapshotRestorer}만 읽는다.
 * <p>
 * 열 대응: {@code request_snapshot}(의미 요청 문서, {@link RunSnapshotCodec}), {@code request_fingerprint}
 * (internal-run-v1 fingerprint), {@code policy_snapshot}(고정 정책·history 경계 기록), {@code viewer_snapshot}·
 * {@code corpus_snapshot}(삭제 fencing·감사용 사본), {@code request_deadline_at}, {@code budget_policy_version}.
 * 어느 문서에도 grant·read URL·JWT가 없다.
 *
 * @param requestId       브라우저 requestId(= internal request_id)
 * @param viewerSnapshot  Viewer Focus가 없으면 null
 */
public record RunSnapshot(
        UUID runId,
        String requestId,
        long userId,
        long noteId,
        String requestFingerprint,
        JsonNode requestSnapshot,
        JsonNode policySnapshot,
        JsonNode viewerSnapshot,
        JsonNode corpusSnapshot,
        String budgetPolicyVersion,
        Instant requestDeadlineAt,
        List<AssetDependency> dependencies) {

    /** run이 고정한 Note Asset 의존성(삭제 fencing 대상). */
    public record AssetDependency(long assetId, RunAssetDependencyKind kind, int contentVersion) {
    }

    public RunSnapshot {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        Objects.requireNonNull(requestSnapshot, "requestSnapshot");
        Objects.requireNonNull(policySnapshot, "policySnapshot");
        Objects.requireNonNull(corpusSnapshot, "corpusSnapshot");
        Objects.requireNonNull(budgetPolicyVersion, "budgetPolicyVersion");
        Objects.requireNonNull(requestDeadlineAt, "requestDeadlineAt");
        dependencies = List.copyOf(dependencies);
    }

    /** 접수 transaction에서 insert할 PENDING run. 메시지 ID와 생성 시각은 접수가 정한다. */
    public AiRun toPendingRun(UUID userMessageId, UUID assistantMessageId, Instant createdAt) {
        return AiRun.pending()
                .id(runId)
                .requestId(requestId)
                .userId(userId)
                .noteId(noteId)
                .userMessageId(userMessageId)
                .assistantMessageId(assistantMessageId)
                .requestFingerprint(requestFingerprint)
                .requestSnapshot(requestSnapshot.deepCopy())
                .policySnapshot(policySnapshot.deepCopy())
                .budgetPolicyVersion(budgetPolicyVersion)
                .viewerSnapshot(viewerSnapshot == null ? null : viewerSnapshot.deepCopy())
                .corpusSnapshot(corpusSnapshot.deepCopy())
                .requestDeadlineAt(requestDeadlineAt)
                .createdAt(createdAt)
                .build();
    }

    /** run insert 뒤 같은 transaction에서(PENDING·attempt 0일 때만) insert할 의존성 행. */
    public List<RunAssetDependency> assetDependencies(Instant createdAt) {
        return dependencies.stream()
                .map(dependency -> new RunAssetDependency(runId, noteId, dependency.assetId(), dependency.kind(),
                        dependency.contentVersion(), createdAt))
                .toList();
    }
}
