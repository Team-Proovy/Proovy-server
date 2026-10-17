package com.proovy.domain.solution.entity;

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
 * 불변 Verified Solution (verified-solution-v1). 정정은 새 row를 insert하고 이전 row에는
 * SUPERSEDED 상태와 후속 ID만 기록한다(DB trigger). 모든 claim coverage는 direct|supporting이다.
 *
 * <p>{@code solutionId}는 Server identity(UUID)이고, Brain에 VerifiedSolutionRef로 다시 보내는
 * 값과 browser verificationSummary.solutionId는 {@code publicSolutionId}(Brain neutral ID)다.
 */
@Entity
@Table(name = "verified_solutions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VerifiedSolution extends AssignedIdEntity<UUID> {

    @Id
    @Column(name = "solution_id")
    private UUID id;

    @Column(name = "public_solution_id", nullable = false, length = 128, updatable = false)
    private String publicSolutionId;

    @Column(name = "note_id", nullable = false, updatable = false)
    private Long noteId;

    @Column(name = "source_run_id", nullable = false, updatable = false)
    private UUID sourceRunId;

    @Column(name = "assistant_message_id", nullable = false, updatable = false)
    private UUID assistantMessageId;

    @Column(name = "schema_version", nullable = false, length = 40, updatable = false)
    private String schemaVersion;

    @Column(name = "solution_version", nullable = false, updatable = false)
    private Integer solutionVersion;

    @Column(name = "problem_statement", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String problemStatement;

    @Type(JsonBinaryType.class)
    @Column(name = "problem_conditions", columnDefinition = "jsonb", nullable = false, updatable = false)
    private JsonNode problemConditions;

    @Column(name = "solution_text", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String solutionText;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb", nullable = false, updatable = false)
    private JsonNode claims;

    @Type(JsonBinaryType.class)
    @Column(name = "step_marks", columnDefinition = "jsonb", nullable = false, updatable = false)
    private JsonNode stepMarks;

    @Type(JsonBinaryType.class)
    @Column(name = "verification_summary", columnDefinition = "jsonb", nullable = false, updatable = false)
    private JsonNode verificationSummary;

    @Column(name = "verification_version", nullable = false, length = 64, updatable = false)
    private String verificationVersion;

    @Type(JsonBinaryType.class)
    @Column(name = "source_ref_ids", columnDefinition = "jsonb", nullable = false, updatable = false)
    private JsonNode sourceRefIds;

    @Column(name = "overall_coverage", nullable = false, length = 20, updatable = false)
    private String overallCoverage;

    @Column(name = "content_sha256", nullable = false, length = 64, updatable = false)
    private String contentSha256;

    @Column(name = "revises_solution_id", updatable = false)
    private UUID revisesSolutionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VerifiedSolutionStatus status;

    @Column(name = "superseded_by_solution_id")
    private UUID supersededBySolutionId;

    @Column(name = "superseded_at")
    private Instant supersededAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Builder
    private VerifiedSolution(UUID id, String publicSolutionId, Long noteId, UUID sourceRunId,
                             UUID assistantMessageId, String schemaVersion, int solutionVersion,
                             String problemStatement, JsonNode problemConditions, String solutionText,
                             JsonNode claims, JsonNode stepMarks, JsonNode verificationSummary,
                             String verificationVersion, JsonNode sourceRefIds, String overallCoverage,
                             String contentSha256, UUID revisesSolutionId, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.publicSolutionId = Objects.requireNonNull(publicSolutionId);
        this.noteId = Objects.requireNonNull(noteId);
        this.sourceRunId = Objects.requireNonNull(sourceRunId);
        this.assistantMessageId = Objects.requireNonNull(assistantMessageId);
        this.schemaVersion = Objects.requireNonNull(schemaVersion);
        this.solutionVersion = solutionVersion;
        this.problemStatement = Objects.requireNonNull(problemStatement);
        this.problemConditions = Objects.requireNonNull(problemConditions);
        this.solutionText = Objects.requireNonNull(solutionText);
        this.claims = Objects.requireNonNull(claims);
        this.stepMarks = Objects.requireNonNull(stepMarks);
        this.verificationSummary = Objects.requireNonNull(verificationSummary);
        this.verificationVersion = Objects.requireNonNull(verificationVersion);
        this.sourceRefIds = Objects.requireNonNull(sourceRefIds);
        this.overallCoverage = Objects.requireNonNull(overallCoverage);
        this.contentSha256 = Objects.requireNonNull(contentSha256);
        this.revisesSolutionId = revisesSolutionId;
        this.status = VerifiedSolutionStatus.ACTIVE;
        this.createdAt = Objects.requireNonNull(createdAt);
    }
}
