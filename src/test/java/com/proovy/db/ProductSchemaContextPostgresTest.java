package com.proovy.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proovy.domain.asset.entity.Asset;
import com.proovy.domain.asset.entity.AssetStatus;
import com.proovy.domain.asset.repository.AssetRepository;
import com.proovy.domain.conversation.entity.ChatMessage;
import com.proovy.domain.conversation.entity.MessageStatus;
import com.proovy.domain.conversation.entity.TurnAttachment;
import com.proovy.domain.conversation.repository.ChatMessageRepository;
import com.proovy.domain.conversation.repository.TurnAttachmentRepository;
import com.proovy.domain.note.entity.Note;
import com.proovy.domain.note.repository.NoteRepository;
import com.proovy.domain.run.entity.*;
import com.proovy.domain.run.repository.AiRunEventRepository;
import com.proovy.domain.run.repository.AiRunRepository;
import com.proovy.domain.run.repository.RunAssetDependencyRepository;
import com.proovy.domain.solution.entity.VerifiedSolution;
import com.proovy.domain.solution.entity.VerifiedSolutionStatus;
import com.proovy.domain.solution.repository.VerifiedSolutionRepository;
import com.proovy.domain.usage.entity.RequestUsageCharge;
import com.proovy.domain.usage.entity.RequestUsageCompensation;
import com.proovy.domain.usage.entity.UsageCompensationReason;
import com.proovy.domain.usage.repository.RequestUsageChargeRepository;
import com.proovy.domain.usage.repository.RequestUsageCompensationRepository;
import com.proovy.domain.user.entity.User;
import com.proovy.domain.user.repository.UserRepository;
import com.proovy.support.db.PostgresTestDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 빈 Postgres에서 Flyway baseline만으로 애플리케이션 context가 기동하고(Hibernate ddl-auto=validate),
 * 신규 Note 중심 엔티티가 ChatSession 없이 저장·조회되는지 검사한다.
 */
@Tag("postgres")
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "gcs.project-id=proovy-test",
        "gcs.endpoint=http://127.0.0.1:1",
        "jwt.secret=test-jwt-secret-test-jwt-secret-0123456789",
        "oauth.kakao.client-id=test", "oauth.kakao.client-secret=test",
        "oauth.naver.client-id=test", "oauth.naver.client-secret=test",
        "openrouter.api.key=test",
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1",
        "spring.task.scheduling.pool.size=1",
        "proovy.subscription.plan-transition-fixed-delay-ms=3600000",
        "proovy.subscription.auto-renew-fixed-delay-ms=3600000"
})
class ProductSchemaContextPostgresTest {

    private static PostgresTestDatabase db;

    @BeforeAll
    static void createDatabase() {
        // 빈 database만 만든다. schema는 애플리케이션 기동 시 Flyway가 생성한다.
        db = PostgresTestDatabase.createEmpty();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> db.jdbcUrl());
        registry.add("spring.datasource.username", PostgresTestDatabase::adminUser);
        registry.add("spring.datasource.password", PostgresTestDatabase::adminPassword);
    }

    @AfterAll
    static void dropDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @Autowired UserRepository userRepository;
    @Autowired NoteRepository noteRepository;
    @Autowired AssetRepository assetRepository;
    @Autowired ChatMessageRepository chatMessageRepository;
    @Autowired TurnAttachmentRepository turnAttachmentRepository;
    @Autowired AiRunRepository aiRunRepository;
    @Autowired RunAssetDependencyRepository runAssetDependencyRepository;
    @Autowired AiRunEventRepository aiRunEventRepository;
    @Autowired VerifiedSolutionRepository verifiedSolutionRepository;
    @Autowired RequestUsageChargeRepository chargeRepository;
    @Autowired RequestUsageCompensationRepository compensationRepository;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper objectMapper;

    @Test
    void noteCentricModelRoundTripsWithoutChatSession() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID userMessageId = UUID.randomUUID();
        UUID assistantMessageId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        String requestId = UUID.randomUUID().toString();

        Long noteId = tx.execute(status -> {
            User user = userRepository.save(User.builder().nickname("context").build());
            Note note = noteRepository.save(Note.builder().user(user).title("새 노트").build());

            ObjectNode input = objectMapper.createObjectNode().put("text", "x^2=4를 풀어줘");
            chatMessageRepository.save(ChatMessage.user(userMessageId, note, input, now));
            chatMessageRepository.save(ChatMessage.assistantPlaceholder(assistantMessageId, note, now.plusMillis(1)));

            aiRunRepository.save(AiRun.pending()
                    .id(runId).requestId(requestId).userId(user.getId()).noteId(note.getId())
                    .userMessageId(userMessageId).assistantMessageId(assistantMessageId)
                    .requestFingerprint("0".repeat(64))
                    .requestSnapshot(objectMapper.createObjectNode().set("input", input))
                    .policySnapshot(objectMapper.createObjectNode().put("budget_policy_version", "budget-v1"))
                    .budgetPolicyVersion("budget-v1")
                    .requestDeadlineAt(now.plusSeconds(600)).createdAt(now)
                    .build());
            aiRunEventRepository.save(AiRunEvent.builder()
                    .runId(runId).noteId(note.getId()).seq(1).eventType(AiRunEventType.RUN_CREATED)
                    .payload(objectMapper.createObjectNode().put("status", "PENDING")).createdAt(now)
                    .build());
            turnAttachmentRepository.save(TurnAttachment.builder()
                    .id(UUID.randomUUID()).noteId(note.getId()).sourceMessageId(userMessageId).attachmentIndex(0)
                    .objectKey("turn/1").sha256("a".repeat(64)).mimeType("image/png").byteSize(100)
                    .pixelWidth(10).pixelHeight(10).pixelCount(100L)
                    .uploadPolicyVersion("upload-v1").pageUnderstandingVersion("pu-v1").imageNormalizationVersion("img-v1")
                    .createdAt(now).build());
            chargeRepository.save(RequestUsageCharge.builder()
                    .requestId(requestId).userId(user.getId()).runId(runId).usageUnits(1)
                    .chargedDaily(1).chargedFree(0).chargedPaid(0).chargedAt(now).build());
            return note.getId();
        });

        AiRun run = aiRunRepository.findActiveByNoteId(noteId).orElseThrow();
        assertThat(run.getId()).isEqualTo(runId);
        assertThat(run.getStatus()).isEqualTo(AiRunStatus.PENDING);
        assertThat(run.getAttempt()).isZero();
        assertThat(aiRunRepository.findByRequestId(requestId)).isPresent();
        assertThat(chatMessageRepository.findByNoteIdOrdered(noteId))
                .extracting(ChatMessage::getId).containsExactly(userMessageId, assistantMessageId);
        assertThat(chatMessageRepository.findById(assistantMessageId).orElseThrow().getStatus())
                .isEqualTo(MessageStatus.PENDING);
        assertThat(aiRunEventRepository.findByRunIdAndSeqGreaterThanOrderBySeqAsc(runId, 0))
                .extracting(AiRunEvent::getEventType).containsExactly(AiRunEventType.RUN_CREATED);
        assertThat(turnAttachmentRepository.findBySourceMessageIdOrderByAttachmentIndexAsc(userMessageId)).hasSize(1);

        // 같은 Note의 두 번째 Active Run은 DB가 거부한다
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            Note note = noteRepository.findById(noteId).orElseThrow();
            UUID um = UUID.randomUUID();
            UUID am = UUID.randomUUID();
            chatMessageRepository.save(ChatMessage.user(um, note, objectMapper.createObjectNode().put("text", "다음 질문"), now));
            chatMessageRepository.save(ChatMessage.assistantPlaceholder(am, note, now));
            aiRunRepository.saveAndFlush(AiRun.pending()
                    .id(UUID.randomUUID()).requestId(UUID.randomUUID().toString())
                    .userId(note.getUser().getId()).noteId(noteId).userMessageId(um).assistantMessageId(am)
                    .requestFingerprint("1".repeat(64)).requestSnapshot(objectMapper.createObjectNode())
                    .policySnapshot(objectMapper.createObjectNode()).budgetPolicyVersion("budget-v1")
                    .requestDeadlineAt(now.plusSeconds(600)).createdAt(now).build());
        })).isInstanceOf(DataIntegrityViolationException.class);
        // rollback으로 두 번째 요청의 메시지도 남지 않는다
        assertThat(chatMessageRepository.countByNoteId(noteId)).isEqualTo(2);

        // 보상 1회 · wire enum converter 왕복
        compensationRepository.saveAndFlush(RequestUsageCompensation.builder()
                .requestId(requestId).reason(UsageCompensationReason.PENDING_CANCELED)
                .restoredDaily(1).restoredFree(0).restoredPaid(0).compensatedAt(now).build());
        assertThat(compensationRepository.findById(requestId).orElseThrow().getCreditAmount()).isEqualTo(1);
    }

    @Test
    void verifiedSolutionAndDependencyEntitiesMapToBaseline() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID runId = UUID.randomUUID();
        UUID assistantMessageId = UUID.randomUUID();
        UUID solutionId = UUID.randomUUID();

        tx.executeWithoutResult(status -> {
            User user = userRepository.save(User.builder().nickname("solution").build());
            Note note = noteRepository.save(Note.builder().user(user).title("solution note").build());
            UUID userMessageId = UUID.randomUUID();
            chatMessageRepository.save(ChatMessage.user(userMessageId, note,
                    objectMapper.createObjectNode().put("text", "풀어줘"), now));
            chatMessageRepository.save(ChatMessage.assistantPlaceholder(assistantMessageId, note, now));
            aiRunRepository.save(AiRun.pending()
                    .id(runId).requestId("req-" + runId).userId(user.getId()).noteId(note.getId())
                    .userMessageId(userMessageId).assistantMessageId(assistantMessageId)
                    .requestFingerprint("2".repeat(64)).requestSnapshot(objectMapper.createObjectNode())
                    .policySnapshot(objectMapper.createObjectNode()).budgetPolicyVersion("budget-v1")
                    .requestDeadlineAt(now.plusSeconds(600)).createdAt(now).build());
            Asset asset = assetRepository.save(Asset.builder()
                    .userId(user.getId()).noteId(note.getId()).fileName("calc.pdf").fileSize(10L)
                    .mimeType("application/pdf").objectKey("users/x/calc.pdf").source(Asset.AssetSource.upload)
                    .status(AssetStatus.ACTIVE).build());
            aiRunRepository.flush();
            assetRepository.flush();
            runAssetDependencyRepository.save(new RunAssetDependency(runId, note.getId(), asset.getId(),
                    RunAssetDependencyKind.VIEWER_FOCUS, asset.getContentVersion(), now));
            verifiedSolutionRepository.save(VerifiedSolution.builder()
                    .id(solutionId).publicSolutionId("solution-1").noteId(note.getId()).sourceRunId(runId)
                    .assistantMessageId(assistantMessageId).schemaVersion("verified-solution-v1").solutionVersion(1)
                    .problemStatement("x^2=4").problemConditions(objectMapper.createArrayNode())
                    .solutionText("x=±2")
                    .claims(objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                            .put("claim_id", "claim-1").put("coverage", "direct")))
                    .stepMarks(objectMapper.createArrayNode())
                    .verificationSummary(objectMapper.createObjectNode().put("overall_coverage", "direct"))
                    .verificationVersion("chain-verification-report-v3")
                    .sourceRefIds(objectMapper.createArrayNode()).overallCoverage("direct")
                    .contentSha256("3".repeat(64)).createdAt(now).build());
        });

        VerifiedSolution saved = verifiedSolutionRepository.findById(solutionId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(VerifiedSolutionStatus.ACTIVE);
        assertThat(saved.getClaims().get(0).get("coverage").asText()).isEqualTo("direct");
        assertThat(verifiedSolutionRepository.findByAssistantMessageId(assistantMessageId)).hasSize(1);
        assertThat(runAssetDependencyRepository.findByRunId(runId))
                .singleElement()
                .satisfies(dep -> {
                    assertThat(dep.getDependencyKind()).isEqualTo(RunAssetDependencyKind.VIEWER_FOCUS);
                    assertThat(dep.getContentVersion()).isEqualTo(1);
                });
    }
}
