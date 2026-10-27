package com.proovy.domain.run.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proovy.domain.run.brain.AssetMime;
import com.proovy.domain.run.brain.ExecutionUsage;
import com.proovy.domain.run.brain.HistoryMessage;
import com.proovy.domain.run.brain.HistoryRole;
import com.proovy.domain.run.brain.InternalRunRequest;
import com.proovy.domain.run.brain.SolutionReferenceStatus;
import com.proovy.domain.run.brain.TurnAttachmentRef;
import com.proovy.domain.run.brain.VerifiedSolutionRef;
import com.proovy.domain.run.brain.ViewerContext;
import com.proovy.domain.run.brain.ViewerSelection;
import com.proovy.domain.run.contract.ConversationRunRequest;
import com.proovy.domain.run.contract.PageSelection;
import com.proovy.domain.run.contract.RequestedOutput;
import com.proovy.domain.run.contract.RequestedTask;
import com.proovy.domain.run.contract.ViewerFocus;
import com.proovy.domain.run.entity.RunAssetDependencyKind;
import com.proovy.domain.run.snapshot.RunAssetFacts.CurrentAttachment;
import com.proovy.domain.run.snapshot.RunAssetFacts.ViewerAsset;
import com.proovy.domain.run.snapshot.RunSnapshotSources.AttachmentRow;
import com.proovy.domain.run.snapshot.RunSnapshotSources.CompletedTurn;
import com.proovy.domain.run.snapshot.RunSnapshotSources.CorpusAsset;
import com.proovy.domain.run.snapshot.RunSnapshotSources.SolutionRow;
import com.proovy.global.response.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/**
 * snapshot 구성 규칙을 메모리 source로 검사한다. SQL과 저장·복원은 {@code RunSnapshotPostgresTest}가 본다.
 */
class RunSnapshotFactoryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long USER = 1001L;
    private static final long NOTE = 2001L;
    private static final Instant NOW = Instant.parse("2026-10-17T09:00:00.123456789Z");
    private static final String REQUEST_ID = "0b7c4a52-6f9e-4b8a-9d61-3f2a1c5e7d90";

    private final FakeSources sources = new FakeSources();
    private final FakeAssetFacts facts = new FakeAssetFacts();

    // ------------------------------------------------------------------
    // 기본 경로·정책 고정
    // ------------------------------------------------------------------

    @Test
    void textOnlyRequestInAnEmptyNoteFixesInputPolicyAndDeadline() {
        RunSnapshot snapshot = factory(defaults()).create(UUID.randomUUID(), USER, NOTE,
                request("x^2 - 5x + 6 = 0을 풀어줘", null), NOW);

        InternalRunRequest delivered = firstDelivery(snapshot, RunAssetAccess.NONE);
        assertThat(delivered.input().text()).isEqualTo("x^2 - 5x + 6 = 0을 풀어줘");
        assertThat(delivered.input().locale()).isEqualTo("ko-KR");
        assertThat(delivered.input().requestedTasks()).isEmpty(); // 기본값을 채우지 않는다(Brain 추론)
        assertThat(delivered.history()).isEmpty();
        assertThat(delivered.verifiedSolutionRefs()).isEmpty();
        assertThat(delivered.turnAttachmentRefs()).isEmpty();
        assertThat(delivered.viewerContext()).isNull();
        assertThat(delivered.turnAttachments()).isEmpty();
        assertThat(delivered.conversationId()).isEqualTo("2001");
        assertThat(delivered.userId()).isEqualTo("1001");
        assertThat(delivered.requestId()).isEqualTo(REQUEST_ID);
        assertThat(delivered.retrievalScope().corpusVersion())
                .isEqualTo(RunSnapshotFactory.corpusVersion(List.of()));
        assertThat(delivered.executionOptions().usage()).isEqualTo(ExecutionUsage.ZERO);
        assertThat(delivered.executionOptions().budgetPolicyVersion()).isEqualTo("budget-v1");

        Instant deadline = Instant.parse("2026-10-17T09:03:00.123456Z");
        assertThat(snapshot.requestDeadlineAt()).isEqualTo(deadline);
        assertThat(delivered.executionOptions().requestDeadlineAt().toInstant()).isEqualTo(deadline);
        assertThat(snapshot.requestFingerprint()).isEqualTo(delivered.fingerprint().sha256());
        assertThat(snapshot.viewerSnapshot()).isNull();
        assertThat(snapshot.dependencies()).isEmpty();
        assertThat(snapshot.policySnapshot().at("/history/max_tokens").asInt()).isEqualTo(24_000);
        assertThat(snapshot.policySnapshot().at("/history/messages").asInt()).isZero();
        assertThat(snapshot.policySnapshot().at("/limits/max_llm_calls").asLong()).isEqualTo(80);
        assertThat(snapshot.policySnapshot().get("snapshot_version").asText()).isEqualTo("run-snapshot-v1");
    }

    @Test
    void noteThatIsMissingForeignOrDeletingIsRejectedBeforeReadingAnything() {
        sources.ownedNote = false;
        assertThatThrownBy(() -> factory(defaults()).create(UUID.randomUUID(), USER, NOTE, request("q", null), NOW))
                .isInstanceOf(RunSnapshotRejectedException.class)
                .extracting(e -> ((RunSnapshotRejectedException) e).getErrorCode()).isEqualTo(ErrorCode.RUN4041);
        assertThat(sources.turnReads).isZero();
    }

    @Test
    void laterSettingsDoNotChangeAStoredSnapshot() {
        sources.turn("이전 질문", "이전 답");
        ConversationRunRequest request = request("다음 질문", null);
        UUID runId = UUID.randomUUID();
        RunSnapshot stored = factory(defaults()).create(runId, USER, NOTE, request, NOW);
        InternalRunRequest before = firstDelivery(stored, RunAssetAccess.NONE);

        // 설정이 바뀐 뒤 같은 입력으로 새로 만들면 다른 정책이 고정된다…
        RunSnapshotProperties changed = properties("en-US", 24_000, Duration.ofMinutes(10), "budget-v2", 7);
        RunSnapshot rebuilt = factory(changed).create(runId, USER, NOTE, request, NOW);
        assertThat(rebuilt.requestFingerprint()).isNotEqualTo(stored.requestFingerprint());
        assertThat(rebuilt.requestDeadlineAt()).isAfter(stored.requestDeadlineAt());

        // …하지만 저장된 snapshot의 복원은 저장 문서만 쓴다.
        InternalRunRequest after = firstDelivery(stored, RunAssetAccess.NONE);
        assertThat(after).isEqualTo(before);
        assertThat(after.input().locale()).isEqualTo("ko-KR");
        assertThat(after.executionOptions().limits().maxLlmCalls()).isEqualTo(80);
        assertThat(after.fingerprint().sha256()).isEqualTo(stored.requestFingerprint());
    }

    // ------------------------------------------------------------------
    // history·참조 범위
    // ------------------------------------------------------------------

    @Test
    void historyIsBoundedToTheNewestTurnsAndReferencesFollowTheKeptRange() {
        // 오래된 turn(잘림) — 큰 답과 solution·첨부
        CompletedTurn old = sources.turn("아주 오래된 질문", "가".repeat(23_960));
        sources.solution(old, "solution-1", false, null);
        sources.attachment(old);
        // 최신 turn(남음)
        CompletedTurn recent = sources.turn("최근 질문", "최근 답");
        sources.solution(recent, "solution-2", false, null);
        AttachmentRow recentAttachment = sources.attachment(recent);

        RunSnapshot snapshot = factory(defaults()).create(UUID.randomUUID(), USER, NOTE,
                request("후속 질문", null), NOW);
        InternalRunRequest delivered = firstDelivery(snapshot, RunAssetAccess.NONE);

        assertThat(delivered.history()).extracting(HistoryMessage::messageId).containsExactly(
                old.assistantMessageId().toString(),
                recent.userMessageId().toString(), recent.assistantMessageId().toString());
        // 최근 두 메시지(12 + 13) + 오래된 답(23,968) = 23,993. 오래된 질문(17)은 넘쳐 잘린다
        assertThat(delivered.verifiedSolutionRefs()).extracting(VerifiedSolutionRef::solutionId)
                .containsExactly("solution-1", "solution-2");
        // 오래된 user 메시지가 잘렸으므로 그 첨부는 참조할 수 없다
        assertThat(delivered.turnAttachmentRefs()).extracting(TurnAttachmentRef::attachmentId)
                .containsExactly(recentAttachment.attachmentId().toString());
        assertThat(snapshot.policySnapshot().at("/history/truncated").asBoolean()).isTrue();
        assertThat(snapshot.policySnapshot().at("/history/estimated_tokens").asInt()).isLessThanOrEqualTo(24_000);
    }

    @Test
    void referencesOfTurnsBeyondTheTokenBoundAreNeverSent() {
        CompletedTurn truncated = sources.turn("오래된 질문", "오래된 답");
        sources.solution(truncated, "solution-1", false, null);
        sources.attachment(truncated);
        CompletedTurn big = sources.turn("큰 질문", "가".repeat(23_990));

        InternalRunRequest delivered = firstDelivery(factory(defaults()).create(UUID.randomUUID(), USER, NOTE,
                request("후속", null), NOW), RunAssetAccess.NONE);

        assertThat(delivered.history()).extracting(HistoryMessage::messageId)
                .containsExactly(big.assistantMessageId().toString());
        assertThat(delivered.verifiedSolutionRefs()).isEmpty();
        assertThat(delivered.turnAttachmentRefs()).isEmpty();
        // 조회도 남은 메시지 범위로만 했다
        assertThat(sources.solutionQueries).allSatisfy(ids ->
                assertThat(ids).containsOnly(big.assistantMessageId()));
    }

    @Test
    void aLongNewestAnswerKeepsItsHistoryAndSolutionReference() {
        CompletedTurn older = sources.turn("이전 질문", "이전 답");
        sources.solution(older, "solution-1", false, null);
        CompletedTurn proof = sources.turn("증명해줘", "증".repeat(30_000));
        sources.solution(proof, "solution-2", false, null);

        RunSnapshot snapshot = factory(defaults()).create(UUID.randomUUID(), USER, NOTE,
                request("그 풀이 3단계 다시 설명해줘", null), NOW);
        InternalRunRequest delivered = firstDelivery(snapshot, RunAssetAccess.NONE);

        assertThat(delivered.history()).extracting(HistoryMessage::messageId)
                .containsExactly(proof.assistantMessageId().toString());
        assertThat(delivered.verifiedSolutionRefs()).extracting(VerifiedSolutionRef::solutionId)
                .containsExactly("solution-2");
        assertThat(snapshot.policySnapshot().at("/history/estimated_tokens").asInt()).isEqualTo(30_008);
        assertThat(snapshot.policySnapshot().at("/history/truncated").asBoolean()).isTrue();
    }

    @Test
    void turnsBeyondTheFetchedWindowMarkTheHistoryTruncated() {
        for (int i = 0; i < 101; i++) {
            sources.turn("같은 질문", " "); // 모두 건너뛰는 후보
        }
        RunSnapshot snapshot = factory(defaults()).create(UUID.randomUUID(), USER, NOTE, request("같은 질문", null),
                NOW);
        assertThat(firstDelivery(snapshot, RunAssetAccess.NONE).history()).isEmpty();
        assertThat(snapshot.policySnapshot().at("/history/truncated").asBoolean()).isTrue();
        assertThat(sources.lastLimit).isEqualTo(101);
    }

    @Test
    void historyCarriesOnlyThePublicPlainTextOfCompletedTurns() {
        CompletedTurn turn = sources.turn("정답은 숨기고 풀이 과정만 보여줘", "풀이 과정: 양변을 정리하면 ... (답은 숨김)");
        sources.solution(turn, "solution-1", false, null);

        InternalRunRequest delivered = firstDelivery(factory(defaults()).create(UUID.randomUUID(), USER, NOTE,
                request("다시 설명해줘", null), NOW), RunAssetAccess.NONE);

        assertThat(delivered.history()).extracting(HistoryMessage::text)
                .containsExactly("정답은 숨기고 풀이 과정만 보여줘", "풀이 과정: 양변을 정리하면 ... (답은 숨김)");
        // Verified Solution의 풀이 본문·답은 SolutionRow에 없고 history로 복구되지 않는다
        assertThat(delivered.history()).noneMatch(message -> message.text().contains("x = 2"));
    }

    @Test
    void theCurrentInputIsNotDuplicatedIntoHistory() {
        CompletedTurn earlier = sources.turn("더 자세히 설명해줘", "자세한 설명");

        InternalRunRequest delivered = firstDelivery(factory(defaults()).create(UUID.randomUUID(), USER, NOTE,
                request(" 더 자세히 설명해줘 ", null), NOW), RunAssetAccess.NONE);

        assertThat(delivered.history()).extracting(HistoryMessage::messageId)
                .containsExactly(earlier.assistantMessageId().toString());
    }

    @Test
    void supersededAndActiveSolutionsAreBothReferencedWithTheirStatusAndRevision() {
        CompletedTurn first = sources.turn("풀어줘", "첫 풀이");
        sources.solution(first, "solution-aaaaaaaaaaaaaaaaaaaa-001", true, null);
        CompletedTurn correction = sources.turn("틀린 것 같아 고쳐줘", "정정한 풀이");
        sources.solution(correction, "solution-bbbbbbbbbbbbbbbbbbbb-001", false,
                "solution-aaaaaaaaaaaaaaaaaaaa-001");

        List<VerifiedSolutionRef> refs = firstDelivery(factory(defaults()).create(UUID.randomUUID(), USER, NOTE,
                request("다른 방법으로 설명해줘", null), NOW), RunAssetAccess.NONE).verifiedSolutionRefs();

        assertThat(refs).extracting(VerifiedSolutionRef::solutionId, VerifiedSolutionRef::status,
                        VerifiedSolutionRef::revisesSolutionId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("solution-aaaaaaaaaaaaaaaaaaaa-001",
                                SolutionReferenceStatus.SUPERSEDED, null),
                        org.assertj.core.groups.Tuple.tuple("solution-bbbbbbbbbbbbbbbbbbbb-001",
                                SolutionReferenceStatus.ACTIVE, "solution-aaaaaaaaaaaaaaaaaaaa-001"));
        // 대상 생략 시 Brain이 고를 Active Solution은 정정본 하나다
        assertThat(refs).filteredOn(ref -> ref.status() == SolutionReferenceStatus.ACTIVE).hasSize(1);
    }

    @Test
    void supersededMetadataDoesNotChangeTheSolutionContentButANewRevisionChangesTheFingerprint() {
        CompletedTurn turn = sources.turn("풀어줘", "풀이");
        SolutionRow active = sources.solution(turn, "solution-aaaaaaaaaaaaaaaaaaaa-001", false, null);
        ConversationRunRequest request = request("설명해줘", null);
        UUID runId = UUID.randomUUID();
        String before = factory(defaults()).create(runId, USER, NOTE, request, NOW).requestFingerprint();

        sources.solutions.set(sources.solutions.indexOf(active), new SolutionRow(active.solutionId(),
                active.publicSolutionId(), active.assistantMessageId(), active.problemStatement(), active.claims(),
                null, null, true));
        assertThat(factory(defaults()).create(runId, USER, NOTE, request, NOW).requestFingerprint())
                .isEqualTo(before);

        CompletedTurn correction = sources.turn("고쳐줘", "정정");
        sources.solution(correction, "solution-bbbbbbbbbbbbbbbbbbbb-001", false, active.publicSolutionId());
        assertThat(factory(defaults()).create(runId, USER, NOTE, request, NOW).requestFingerprint())
                .isNotEqualTo(before);
    }

    @Test
    void ambiguousBrainSolutionIdsInTheRangeAreNotReferenced() {
        CompletedTurn a = sources.turn("1번", "답 1");
        sources.solution(a, "solution-1", false, null);
        CompletedTurn b = sources.turn("2번", "답 2");
        sources.solution(b, "solution-1", false, null);
        sources.solution(b, "solution-2", false, null);

        List<VerifiedSolutionRef> refs = firstDelivery(factory(defaults()).create(UUID.randomUUID(), USER, NOTE,
                request("설명", null), NOW), RunAssetAccess.NONE).verifiedSolutionRefs();

        assertThat(refs).extracting(VerifiedSolutionRef::solutionId).containsExactly("solution-2");
    }

    @Test
    void aRevisionLinkToAnAmbiguousBrainIdIsDroppedButTheReferenceStays() {
        // 정정 대상 X("solution-1")는 잘린 범위에 있고, 범위 안의 다른 run이 같은 순번형 ID "solution-1"을 쓴다
        UUID truncatedX = UUID.randomUUID();
        CompletedTurn correction = sources.turn("고쳐줘", "정정");
        sources.solutions.add(new SolutionRow(UUID.randomUUID(), "solution-9", correction.assistantMessageId(), "문제",
                claims("근"), truncatedX, "solution-1", false));
        CompletedTurn other = sources.turn("2번", "답 2");
        sources.solution(other, "solution-1", false, null);

        List<VerifiedSolutionRef> refs = firstDelivery(factory(defaults()).create(UUID.randomUUID(), USER, NOTE,
                request("설명", null), NOW), RunAssetAccess.NONE).verifiedSolutionRefs();

        // "solution-1"을 정정 대상으로 실으면 Brain은 범위 안의 다른 풀이로 읽는다 → 정정 관계만 뺀다
        assertThat(refs).extracting(VerifiedSolutionRef::solutionId, VerifiedSolutionRef::revisesSolutionId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("solution-9", null),
                        org.assertj.core.groups.Tuple.tuple("solution-1", null));
    }

    @Test
    void anUnambiguousRevisionLinkIsKept() {
        CompletedTurn first = sources.turn("풀어줘", "풀이");
        sources.solution(first, "solution-1", true, null);
        CompletedTurn correction = sources.turn("고쳐줘", "정정");
        sources.solution(correction, "solution-2", false, "solution-1");

        List<VerifiedSolutionRef> refs = firstDelivery(factory(defaults()).create(UUID.randomUUID(), USER, NOTE,
                request("설명", null), NOW), RunAssetAccess.NONE).verifiedSolutionRefs();

        assertThat(refs).extracting(VerifiedSolutionRef::revisesSolutionId).containsExactly(null, "solution-1");
    }

    @Test
    void longStoredSolutionTextIsBoundedNotRejected() {
        CompletedTurn turn = sources.turn("풀어줘", "풀이");
        sources.solutions.add(new SolutionRow(UUID.randomUUID(), "solution-1", turn.assistantMessageId(),
                "문".repeat(5_000), claims("가".repeat(3_000)), null, null, false));

        VerifiedSolutionRef ref = firstDelivery(factory(defaults()).create(UUID.randomUUID(), USER, NOTE,
                request("설명", null), NOW), RunAssetAccess.NONE).verifiedSolutionRefs().get(0);

        assertThat(ref.problemSummary().codePointCount(0, ref.problemSummary().length())).isEqualTo(4_000);
        assertThat(ref.claims().get(0).statement()).hasSize(2_000);
    }

    @Test
    void malformedStoredSolutionsAreOmittedInsteadOfBlockingTheNote() {
        CompletedTurn turn = sources.turn("풀어줘", "풀이");
        sources.solutions.add(new SolutionRow(UUID.randomUUID(), "solution-1", turn.assistantMessageId(),
                "문제", JSON.createArrayNode(), null, null, false));

        assertThat(firstDelivery(factory(defaults()).create(UUID.randomUUID(), USER, NOTE, request("설명", null),
                NOW), RunAssetAccess.NONE).verifiedSolutionRefs()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Viewer Focus·corpus·현재 첨부
    // ------------------------------------------------------------------

    @Test
    void viewerFocusAndCorpusAreFixedAtSendTimeAndBecomeDependencies() {
        sources.assets.add(new CorpusAsset(3001L, 2));
        sources.assets.add(new CorpusAsset(3003L, 1));
        facts.viewer = new ViewerAsset(3001L, 2, pdfViewer(3, new ViewerSelection(0.1, 0.2, 0.6, 0.7)));

        RunSnapshot snapshot = factory(defaults()).create(UUID.randomUUID(), USER, NOTE,
                request("이 부분 풀어줘", new ViewerFocus("3001", 3, new PageSelection(0.1, 0.2, 0.6, 0.7))), NOW);
        InternalRunRequest delivered = firstDelivery(snapshot,
                new RunAssetAccess(null, "https://storage.example/3001?sig=secret", Map.of()));

        assertThat(delivered.viewerContext().assetId()).isEqualTo("3001");
        assertThat(delivered.viewerContext().page()).isEqualTo(3);
        assertThat(delivered.viewerContext().readUrl()).isEqualTo("https://storage.example/3001?sig=secret");
        assertThat(snapshot.viewerSnapshot().has("read_url")).isFalse();
        assertThat(snapshot.viewerSnapshot().get("content_version").asInt()).isEqualTo(2);
        assertThat(snapshot.corpusSnapshot().get("corpus_version").asText())
                .isEqualTo(delivered.retrievalScope().corpusVersion());
        assertThat(snapshot.corpusSnapshot().get("assets")).hasSize(2);
        assertThat(snapshot.dependencies()).containsExactlyInAnyOrder(
                new RunSnapshot.AssetDependency(3001L, RunAssetDependencyKind.CORPUS_SNAPSHOT, 2),
                new RunSnapshot.AssetDependency(3003L, RunAssetDependencyKind.CORPUS_SNAPSHOT, 1),
                new RunSnapshot.AssetDependency(3001L, RunAssetDependencyKind.VIEWER_FOCUS, 2));
        assertThat(snapshot.assetDependencies(NOW)).hasSize(3)
                .allSatisfy(row -> assertThat(row.getRunId()).isEqualTo(snapshot.runId()));
    }

    @Test
    void corpusVersionChangesWithTheAssetSetOrContentVersionOnly() {
        String base = RunSnapshotFactory.corpusVersion(List.of(new CorpusAsset(1, 1), new CorpusAsset(2, 1)));
        assertThat(RunSnapshotFactory.corpusVersion(List.of(new CorpusAsset(2, 1), new CorpusAsset(1, 1))))
                .isEqualTo(base);
        assertThat(RunSnapshotFactory.corpusVersion(List.of(new CorpusAsset(1, 1), new CorpusAsset(2, 2))))
                .isNotEqualTo(base);
        assertThat(RunSnapshotFactory.corpusVersion(List.of(new CorpusAsset(1, 1)))).isNotEqualTo(base);
        assertThat(base).matches("corpus-[0-9a-f]{64}");
    }

    @Test
    void currentAttachmentsAreFixedWithoutUrlsAndNeedFreshUrlsToBeDelivered() {
        UUID attachment = UUID.randomUUID();
        facts.attachments = List.of(image(attachment, 512_000, 1600, 1200));

        RunSnapshot snapshot = factory(defaults()).create(UUID.randomUUID(), USER, NOTE,
                request("이 사진 풀어줘", null, attachment), NOW);

        assertThat(snapshot.requestSnapshot().toString())
                .doesNotContain("read_url", RunSnapshotFactory.UNISSUED_READ_URL, RunSnapshotFactory.UNISSUED_FENCE);
        assertThatThrownBy(() -> firstDelivery(snapshot, RunAssetAccess.NONE))
                .isInstanceOf(RunSnapshotIntegrityException.class);
        InternalRunRequest delivered = firstDelivery(snapshot, new RunAssetAccess(null, null,
                Map.of(attachment.toString(), "https://storage.example/att?sig=s")));
        assertThat(delivered.turnAttachments()).singleElement()
                .satisfies(item -> assertThat(item.readUrl()).isEqualTo("https://storage.example/att?sig=s"));
    }

    @Test
    void attachmentPolicyAndUnresolvedAttachmentsAreRejected() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        facts.attachments = List.of(image(first, 16 * 1024 * 1024, 100, 100)); // 이미지 15MB 초과
        assertRejected(request("q", null, first), ErrorCode.RUN4221);

        facts.attachments = List.of(image(first, 1_000, 8_000, 6_000)); // 48 megapixels
        assertRejected(request("q", null, first), ErrorCode.RUN4221);

        facts.attachments = List.of(image(first, 1_000, 10, 10)); // 다른 Note·없는 첨부는 resolver가 돌려주지 않는다
        assertRejected(request("q", null, first, second), ErrorCode.RUN4221);

        UUID third = UUID.randomUUID();
        UUID fourth = UUID.randomUUID();
        facts.attachments = List.of(image(first, 1_000, 10, 10), image(second, 1_000, 10, 10),
                image(third, 1_000, 10, 10), image(fourth, 1_000, 10, 10)); // 4개 > 3
        assertRejected(request("q", null, first, second, third, fourth), ErrorCode.RUN4221);
    }

    @Test
    void defaultAssetFactsRejectAttachmentsUntilTheUploadPathExists() {
        StoredRunAssetFacts stored = new StoredRunAssetFacts(null, defaults());
        assertThat(stored.currentAttachments(USER, NOTE, List.of())).isEmpty();
        assertThatThrownBy(() -> stored.currentAttachments(USER, NOTE, List.of(UUID.randomUUID())))
                .isInstanceOf(RunSnapshotRejectedException.class)
                .extracting(e -> ((RunSnapshotRejectedException) e).getErrorCode()).isEqualTo(ErrorCode.RUN4221);
    }

    @Test
    void persistedDocumentsCarryNoCredentialOrControlValue() {
        sources.turn("이전", "답");
        sources.assets.add(new CorpusAsset(3001L, 1));
        facts.viewer = new ViewerAsset(3001L, 1, pdfViewer(1, null));
        UUID attachment = UUID.randomUUID();
        facts.attachments = List.of(image(attachment, 1_000, 10, 10));

        RunSnapshot snapshot = factory(defaults()).create(UUID.randomUUID(), USER, NOTE,
                request("q", new ViewerFocus("3001", 1, null), attachment), NOW);
        InternalRunRequest delivered = firstDelivery(snapshot, new RunAssetAccess("grant-secret-value",
                "https://storage.example/v?sig=viewer-secret",
                Map.of(attachment.toString(), "https://storage.example/a?sig=attachment-secret")));
        assertThat(delivered.assetAccessGrant()).isEqualTo("grant-secret-value");

        for (JsonNode document : List.of(snapshot.requestSnapshot(), snapshot.policySnapshot(),
                snapshot.viewerSnapshot(), snapshot.corpusSnapshot())) {
            assertThat(document.toString()).doesNotContain("secret", "read_url", "asset_access_grant",
                    "fence_token", "\"usage\"", "auth", "jwt", "urn:proovy");
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void assertRejected(ConversationRunRequest request, ErrorCode code) {
        assertThatThrownBy(() -> factory(defaults()).create(UUID.randomUUID(), USER, NOTE, request, NOW))
                .isInstanceOf(RunSnapshotRejectedException.class)
                .extracting(e -> ((RunSnapshotRejectedException) e).getErrorCode()).isEqualTo(code);
    }

    private RunSnapshotFactory factory(RunSnapshotProperties properties) {
        return new RunSnapshotFactory(sources, facts, properties);
    }

    static InternalRunRequest firstDelivery(RunSnapshot snapshot, RunAssetAccess access) {
        return RunSnapshotRestorer.restore(snapshot.requestSnapshot(), snapshot.requestFingerprint(),
                snapshot.requestDeadlineAt(), 1, "fence-1", ExecutionUsage.ZERO, access);
    }

    static RunSnapshotProperties defaults() {
        return properties("ko-KR", 24_000, Duration.ofMinutes(3), "budget-v1", 80);
    }

    static RunSnapshotProperties properties(String locale, int historyTokens, Duration timeout, String budgetVersion,
            long maxLlmCalls) {
        return new RunSnapshotProperties(locale, historyTokens, timeout,
                new RunSnapshotProperties.Budget(budgetVersion, maxLlmCalls, 40, 100_000, 5_000_000, 5, 10, 50),
                new RunSnapshotProperties.Retrieval("se-v1", "chunk-v1", "emb-v1"),
                new RunSnapshotProperties.AssetVersions("upload-v1", "pu-v1", "imgnorm-v1"),
                new RunSnapshotProperties.TurnAttachmentPolicy(3, DataSize.ofMegabytes(30), DataSize.ofMegabytes(20),
                        50, DataSize.ofMegabytes(15), 40_000_000L));
    }

    private static ConversationRunRequest request(String text, ViewerFocus focus, UUID... attachments) {
        return new ConversationRunRequest(REQUEST_ID, String.valueOf(NOTE), text, null,
                List.<RequestedTask>of(), List.<RequestedOutput>of(),
                attachments.length == 0 ? null : java.util.Arrays.stream(attachments).map(UUID::toString).toList(),
                focus);
    }

    private static ViewerContext pdfViewer(int page, ViewerSelection selection) {
        return new ViewerContext("3001", page, selection, "a1".repeat(32), AssetMime.PDF, 1_048_576L, 12,
                null, null, null, "upload-v1", "pu-v1", "imgnorm-v1", null);
    }

    private static CurrentAttachment image(UUID id, long bytes, int width, int height) {
        return new CurrentAttachment(id, "e".repeat(64), AssetMime.PNG, bytes, null, width, height,
                (long) width * height, "upload-v1", "pu-v1", "imgnorm-v1");
    }

    private static JsonNode claims(String statement) {
        return JSON.createArrayNode().add(JSON.createObjectNode()
                .put("claim_id", "claim-1").put("statement", statement).put("coverage", "direct"));
    }

    /** 최신 turn을 마지막에 추가하는 메모리 source. */
    static final class FakeSources implements RunSnapshotSources {
        boolean ownedNote = true;
        int turnReads;
        int lastLimit;
        final List<CompletedTurn> turns = new ArrayList<>();
        final List<SolutionRow> solutions = new ArrayList<>();
        final List<AttachmentRow> attachments = new ArrayList<>();
        final List<CorpusAsset> assets = new ArrayList<>();
        final List<Collection<UUID>> solutionQueries = new ArrayList<>();

        CompletedTurn turn(String user, String assistant) {
            CompletedTurn turn = new CompletedTurn(UUID.randomUUID(), user, UUID.randomUUID(), assistant);
            turns.add(turn);
            return turn;
        }

        /** {@code revises}는 이미 추가한 solution의 Brain ID(가장 최근 것)다. */
        SolutionRow solution(CompletedTurn turn, String publicId, boolean superseded, String revises) {
            UUID revisesId = revises == null ? null : solutions.stream()
                    .filter(row -> row.publicSolutionId().equals(revises)).reduce((a, b) -> b).orElseThrow()
                    .solutionId();
            SolutionRow row = new SolutionRow(UUID.randomUUID(), publicId, turn.assistantMessageId(), "문제 " + publicId,
                    claims("근은 x = 2"), revisesId, revises, superseded);
            solutions.add(row);
            return row;
        }

        AttachmentRow attachment(CompletedTurn turn) {
            AttachmentRow row = new AttachmentRow(UUID.randomUUID(), turn.userMessageId(), 0, "d".repeat(64),
                    "image/png", "pu-v1", "imgnorm-v1", "사진");
            attachments.add(row);
            return row;
        }

        @Override
        public boolean isActiveNoteOwnedBy(long noteId, long userId) {
            return ownedNote && noteId == NOTE && userId == USER;
        }

        @Override
        public List<CompletedTurn> completedTurnsNewestFirst(long noteId, int limit) {
            turnReads++;
            lastLimit = limit;
            List<CompletedTurn> newestFirst = new ArrayList<>(turns);
            java.util.Collections.reverse(newestFirst);
            return newestFirst.stream().limit(limit).toList();
        }

        @Override
        public List<SolutionRow> solutionsForMessages(long noteId, Collection<UUID> assistantMessageIds) {
            solutionQueries.add(List.copyOf(assistantMessageIds));
            return solutions.stream().filter(row -> assistantMessageIds.contains(row.assistantMessageId())).toList();
        }

        @Override
        public List<AttachmentRow> attachmentsForMessages(long noteId, Collection<UUID> userMessageIds) {
            return attachments.stream().filter(row -> userMessageIds.contains(row.sourceMessageId())).toList();
        }

        @Override
        public List<CorpusAsset> activeAssets(long noteId) {
            return List.copyOf(assets);
        }
    }

    static final class FakeAssetFacts implements RunAssetFacts {
        ViewerAsset viewer;
        List<CurrentAttachment> attachments = List.of();

        @Override
        public ViewerAsset viewerAsset(long userId, long noteId, ViewerFocus focus) {
            if (viewer == null) {
                throw RunSnapshotRejectedException.referenceUnavailable();
            }
            return viewer;
        }

        @Override
        public List<CurrentAttachment> currentAttachments(long userId, long noteId, List<UUID> attachmentIds) {
            return attachments.stream().filter(item -> attachmentIds.contains(item.attachmentId())).toList();
        }
    }
}
