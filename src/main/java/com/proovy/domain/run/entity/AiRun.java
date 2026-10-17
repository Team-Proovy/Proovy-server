package com.proovy.domain.run.entity;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import com.proovy.global.entity.AssignedIdEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Brain 실행 하나의 Server 측 기록 (docs/plan/05·12, spec 07).
 *
 * <p>요청 식별·불변 snapshot·deadline 컬럼은 생성 시 고정되며 DB trigger가 변경을 거부한다.
 * 상태 전이·lease·attempt/fence·누적 사용량은 엔티티 dirty checking이 아니라 후속 coordinator의
 * 조건부 UPDATE(WHERE status/attempt/fence ...)로만 바꾼다. 그래서 이 엔티티는 생성 factory와
 * 조회용 getter만 제공한다.
 */
@Entity
@Table(name = "ai_runs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiRun extends AssignedIdEntity<UUID> {

    @Id
    @Column(name = "run_id")
    private UUID id;

    @Column(name = "request_id", nullable = false, length = 128, updatable = false)
    private String requestId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "note_id", nullable = false, updatable = false)
    private Long noteId;

    @Column(name = "user_message_id", nullable = false, updatable = false)
    private UUID userMessageId;

    @Column(name = "assistant_message_id", nullable = false, updatable = false)
    private UUID assistantMessageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiRunStatus status;

    // ===== 실행 제어 =====

    @Column(nullable = false)
    private Integer attempt;

    @Column(name = "fence_token", length = 128)
    private String fenceToken;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    @Column(name = "lease_owner", length = 128)
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    /** Asset Access Grant의 SHA-256 digest. grant 원문·signed URL은 저장하지 않는다. */
    @Column(name = "grant_digest", length = 64)
    private String grantDigest;

    @Column(name = "grant_expires_at")
    private Instant grantExpiresAt;

    @Column(name = "runtime_cleanup_at")
    private Instant runtimeCleanupAt;

    /** run별 마지막 durable event seq. event append는 이 값을 조건부로 증가시켜 seq를 배정한다. */
    @Column(name = "last_event_seq", nullable = false)
    private Long lastEventSeq;

    // ===== 불변 요청 =====

    @Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
    private String requestFingerprint;

    @Type(JsonBinaryType.class)
    @Column(name = "request_snapshot", columnDefinition = "jsonb", nullable = false, updatable = false)
    private JsonNode requestSnapshot;

    @Type(JsonBinaryType.class)
    @Column(name = "policy_snapshot", columnDefinition = "jsonb", nullable = false, updatable = false)
    private JsonNode policySnapshot;

    @Column(name = "budget_policy_version", nullable = false, length = 64, updatable = false)
    private String budgetPolicyVersion;

    @Type(JsonBinaryType.class)
    @Column(name = "viewer_snapshot", columnDefinition = "jsonb", updatable = false)
    private JsonNode viewerSnapshot;

    @Type(JsonBinaryType.class)
    @Column(name = "corpus_snapshot", columnDefinition = "jsonb", updatable = false)
    private JsonNode corpusSnapshot;

    @Column(name = "request_deadline_at", nullable = false, updatable = false)
    private Instant requestDeadlineAt;

    // ===== 누적 사용 예산 (단조 증가) =====

    @Column(name = "used_llm_calls", nullable = false)
    private Long usedLlmCalls;

    @Column(name = "used_tool_calls", nullable = false)
    private Long usedToolCalls;

    @Column(name = "used_output_tokens", nullable = false)
    private Long usedOutputTokens;

    @Column(name = "used_cost_micros", nullable = false)
    private Long usedCostMicros;

    @Column(name = "used_page_understandings", nullable = false)
    private Long usedPageUnderstandings;

    // ===== 결과·실패·취소 =====

    @Convert(converter = ResponseDisposition.Converter.class)
    @Column(name = "response_disposition", length = 30)
    private ResponseDisposition responseDisposition;

    @Convert(converter = ClarificationReason.Converter.class)
    @Column(name = "clarification_reason", length = 40)
    private ClarificationReason clarificationReason;

    @Convert(converter = RunFailureCode.Converter.class)
    @Column(name = "failure_code", length = 40)
    private RunFailureCode failureCode;

    @Convert(converter = RunFailureClass.Converter.class)
    @Column(name = "failure_class", length = 20)
    private RunFailureClass failureClass;

    @Convert(converter = RunCancelCause.Converter.class)
    @Column(name = "cancel_cause", length = 30)
    private RunCancelCause cancelCause;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_requested_from", length = 20)
    private AiRunStatus cancelRequestedFrom;

    @Column(name = "cancel_requested_at")
    private Instant cancelRequestedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * 접수 transaction에서 저장할 PENDING run. Brain 미배정(attempt 0, fence 없음) 상태다.
     */
    @Builder(builderMethodName = "pending")
    private AiRun(UUID id, String requestId, Long userId, Long noteId,
                  UUID userMessageId, UUID assistantMessageId,
                  String requestFingerprint, JsonNode requestSnapshot, JsonNode policySnapshot,
                  String budgetPolicyVersion, JsonNode viewerSnapshot, JsonNode corpusSnapshot,
                  Instant requestDeadlineAt, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.noteId = Objects.requireNonNull(noteId, "noteId");
        this.userMessageId = Objects.requireNonNull(userMessageId, "userMessageId");
        this.assistantMessageId = Objects.requireNonNull(assistantMessageId, "assistantMessageId");
        this.requestFingerprint = Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        this.requestSnapshot = Objects.requireNonNull(requestSnapshot, "requestSnapshot");
        this.policySnapshot = Objects.requireNonNull(policySnapshot, "policySnapshot");
        this.budgetPolicyVersion = Objects.requireNonNull(budgetPolicyVersion, "budgetPolicyVersion");
        this.viewerSnapshot = viewerSnapshot;
        this.corpusSnapshot = corpusSnapshot;
        this.requestDeadlineAt = Objects.requireNonNull(requestDeadlineAt, "requestDeadlineAt");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.status = AiRunStatus.PENDING;
        this.attempt = 0;
        this.retryCount = 0;
        this.lastEventSeq = 0L;
        this.usedLlmCalls = 0L;
        this.usedToolCalls = 0L;
        this.usedOutputTokens = 0L;
        this.usedCostMicros = 0L;
        this.usedPageUnderstandings = 0L;
    }
}
