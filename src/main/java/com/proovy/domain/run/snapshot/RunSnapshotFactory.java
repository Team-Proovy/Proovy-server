package com.proovy.domain.run.snapshot;

import static com.proovy.domain.run.brain.InternalRunContract.MAX_CLAIM_STATEMENT_CHARS;
import static com.proovy.domain.run.brain.InternalRunContract.MAX_HISTORY_MESSAGES;
import static com.proovy.domain.run.brain.InternalRunContract.MAX_PROBLEM_SUMMARY_CHARS;
import static com.proovy.domain.run.brain.InternalRunContract.MAX_REF_CLAIMS;
import static com.proovy.domain.run.brain.InternalRunContract.MAX_TURN_ATTACHMENT_REFS;
import static com.proovy.domain.run.brain.InternalRunContract.MAX_VERIFIED_SOLUTION_REFS;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proovy.domain.run.brain.AssetMime;
import com.proovy.domain.run.brain.ExecutionLimits;
import com.proovy.domain.run.brain.ExecutionOptions;
import com.proovy.domain.run.brain.ExecutionUsage;
import com.proovy.domain.run.brain.HistoryRole;
import com.proovy.domain.run.brain.InternalRunContract;
import com.proovy.domain.run.brain.InternalRunContractViolation;
import com.proovy.domain.run.brain.InternalRunRequest;
import com.proovy.domain.run.brain.InternalRunWire;
import com.proovy.domain.run.brain.RetrievalScope;
import com.proovy.domain.run.brain.RunInput;
import com.proovy.domain.run.brain.SolutionReferenceStatus;
import com.proovy.domain.run.brain.SummaryCoverage;
import com.proovy.domain.run.brain.TurnAttachment;
import com.proovy.domain.run.brain.TurnAttachmentRef;
import com.proovy.domain.run.brain.VerifiedSolutionRef;
import com.proovy.domain.run.brain.VerifiedSolutionRefClaim;
import com.proovy.domain.run.contract.BrowserRunContract;
import com.proovy.domain.run.contract.CanonicalJson;
import com.proovy.domain.run.contract.ConversationRunRequest;
import com.proovy.domain.run.entity.RunAssetDependencyKind;
import com.proovy.domain.run.snapshot.RunAssetFacts.CurrentAttachment;
import com.proovy.domain.run.snapshot.RunAssetFacts.ViewerAsset;
import com.proovy.domain.run.snapshot.RunSnapshotSources.AttachmentRow;
import com.proovy.domain.run.snapshot.RunSnapshotSources.CompletedTurn;
import com.proovy.domain.run.snapshot.RunSnapshotSources.CorpusAsset;
import com.proovy.domain.run.snapshot.RunSnapshotSources.SolutionRow;
import com.proovy.global.util.HashUtils;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 브라우저 {@link ConversationRunRequest}를 권한·범위 검사 후 불변 {@link RunSnapshot}으로 고정한다.
 * 접수 transaction(1.79) 안에서 Note 권한 검사 뒤, run insert 전에 한 번 부른다. 같은 requestId의 재전송(replay)은
 * 저장된 run을 쓰고 이 메서드를 다시 부르지 않는다.
 * <ul>
 *   <li>history: COMPLETED run의 user/assistant 짝을 최신부터 {@link HistoryBound}로 자른 공개 plain text.
 *       실패·취소·진행 중 turn과 Candidate·숨긴 답·Verified Solution 본문은 들어가지 않는다.</li>
 *   <li>Verified Solution Reference: 남은 history의 assistant 메시지에 묶인 solution만. 조회 시점 status(active·
 *       superseded)와 정정 관계를 그대로 실어 Brain이 대상 생략(Active Solution)과 명시 비교(superseded)를 구분하게 한다.
 *       Brain neutral ID가 이 범위에서 겹치면 그 ID의 참조는 모두 뺀다(모호한 참조를 보내지 않는다).</li>
 *   <li>Turn Attachment Reference: 남은 history의 user 메시지 첨부만.</li>
 *   <li>Viewer Focus·현재 첨부: {@link RunAssetFacts}가 같은 사용자·Note에서 확인한 전송 시점 사실. 자연어 scope는 판정하지 않는다.</li>
 *   <li>corpus: 지금 ACTIVE인 Note Asset 집합과 content version으로 {@code corpus_version}을 만들고 의존성으로 고정한다.</li>
 *   <li>정책: locale·budget version·limits·절대 deadline(now + request-timeout)을 지금 설정에서 한 번만 읽는다.</li>
 * </ul>
 */
@Slf4j
@Component
public class RunSnapshotFactory {

    public static final String SNAPSHOT_VERSION = "run-snapshot-v1";
    /** fingerprint 계산용 임시 control 값. 저장 문서에서 빠지고 전달에 쓰이지 않는다. */
    static final String UNISSUED_FENCE = "snapshot";
    static final String UNISSUED_READ_URL = "urn:proovy:read-url-not-issued";
    private static final String HISTORY_ESTIMATOR = "code-points+8";

    private final RunSnapshotSources sources;
    private final RunAssetFacts assetFacts;
    private final RunSnapshotProperties properties;

    public RunSnapshotFactory(RunSnapshotSources sources, RunAssetFacts assetFacts, RunSnapshotProperties properties) {
        this.sources = sources;
        this.assetFacts = assetFacts;
        this.properties = properties;
    }

    /**
     * @param runId   접수가 발급한 run ID
     * @param userId  인증된 사용자
     * @param noteId  요청 Note(새 Note면 같은 transaction에서 방금 만든 ID)
     * @param request {@code ConversationRunRequestValidator}를 통과한 body
     * @param now     접수 시각. deadline의 기준이다
     * @throws RunSnapshotRejectedException RUN4041(Note) 또는 RUN4221(첨부·Viewer Focus)
     */
    public RunSnapshot create(UUID runId, long userId, long noteId, ConversationRunRequest request, Instant now) {
        if (!sources.isActiveNoteOwnedBy(noteId, userId)) {
            throw RunSnapshotRejectedException.noteUnavailable();
        }
        List<UUID> attachmentIds = request.turnAttachmentIds() == null ? List.of()
                : request.turnAttachmentIds().stream().map(UUID::fromString).toList();
        List<CurrentAttachment> current = assetFacts.currentAttachments(userId, noteId, attachmentIds);
        requireCurrentAttachments(attachmentIds, current);
        ViewerAsset viewer = request.viewerFocus() == null ? null
                : assetFacts.viewerAsset(userId, noteId, request.viewerFocus());

        HistoryBound.Result history = history(noteId, request.text());
        List<VerifiedSolutionRef> solutionRefs = solutionRefs(noteId, history);
        List<TurnAttachmentRef> attachmentRefs = attachmentRefs(noteId, history);

        List<CorpusAsset> corpus = sources.activeAssets(noteId);
        RunSnapshotProperties.Retrieval retrieval = properties.retrieval();
        String noteKey = String.valueOf(noteId);
        RetrievalScope scope = new RetrievalScope(noteKey, corpusVersion(corpus),
                retrieval.searchExtractionVersion(), retrieval.chunkingVersion(), retrieval.embeddingVersion());

        Instant deadline = now.plus(properties.requestTimeout()).truncatedTo(ChronoUnit.MICROS);
        ExecutionLimits limits = properties.budget().limits();
        ExecutionOptions options = new ExecutionOptions(properties.budget().policyVersion(),
                deadline.atOffset(ZoneOffset.UTC), limits, ExecutionUsage.ZERO);

        InternalRunRequest internal;
        try {
            internal = new InternalRunRequest(InternalRunContract.CONTRACT_VERSION, request.requestId(),
                    runId.toString(), String.valueOf(userId), noteKey, noteKey, 1, UNISSUED_FENCE,
                    new RunInput(request.text(), request.latex(), request.requestedTasks(),
                            request.requestedOutputs(), properties.locale()),
                    history.messages(), solutionRefs, attachmentRefs, viewer == null ? null : viewer.context(),
                    scope, current.stream().map(RunSnapshotFactory::unissued).toList(), null, options);
        } catch (InternalRunContractViolation e) {
            // 검증된 브라우저 body와 Server가 고른 범위로는 생기지 않아야 한다(Server 버그).
            throw new IllegalStateException("run snapshot violates internal-run-v1: " + e.getMessage());
        }

        ObjectNode requestSnapshot = RunSnapshotCodec.strip(internal);
        List<RunSnapshot.AssetDependency> dependencies = new ArrayList<>();
        corpus.forEach(asset -> dependencies.add(new RunSnapshot.AssetDependency(asset.assetId(),
                RunAssetDependencyKind.CORPUS_SNAPSHOT, asset.contentVersion())));
        if (viewer != null) {
            dependencies.add(new RunSnapshot.AssetDependency(viewer.assetId(), RunAssetDependencyKind.VIEWER_FOCUS,
                    viewer.contentVersion()));
        }
        return new RunSnapshot(runId, request.requestId(), userId, noteId, internal.fingerprint().sha256(),
                requestSnapshot, policySnapshot(limits, history), viewerSnapshot(requestSnapshot, viewer),
                corpusSnapshot(scope, corpus), properties.budget().policyVersion(), deadline, dependencies);
    }

    private HistoryBound.Result history(long noteId, String currentText) {
        // 메시지 100개 상한이라 최신 100 turn(후보 200개)이면 충분하다. 하나 더 읽어 그보다 오래된 turn이 남았는지만 본다.
        List<CompletedTurn> turns = sources.completedTurnsNewestFirst(noteId, MAX_HISTORY_MESSAGES + 1);
        List<HistoryBound.Candidate> newestFirst = new ArrayList<>();
        for (CompletedTurn turn : turns.subList(0, Math.min(turns.size(), MAX_HISTORY_MESSAGES))) {
            newestFirst.add(new HistoryBound.Candidate(turn.assistantMessageId(), HistoryRole.ASSISTANT,
                    turn.assistantText()));
            newestFirst.add(new HistoryBound.Candidate(turn.userMessageId(), HistoryRole.USER, turn.userText()));
        }
        return HistoryBound.keepNewest(newestFirst, properties.historyMaxTokens(), currentText)
                .truncatedIf(turns.size() > MAX_HISTORY_MESSAGES);
    }

    private List<VerifiedSolutionRef> solutionRefs(long noteId, HistoryBound.Result history) {
        List<UUID> assistantIds = history.messages().stream()
                .filter(message -> message.role() == HistoryRole.ASSISTANT)
                .map(message -> UUID.fromString(message.messageId())).toList();
        // 조회는 남은 history의 assistant 메시지로 한정된다(잘린 범위의 solution은 오지 않는다).
        List<SolutionRow> rows = sources.solutionsForMessages(noteId, assistantIds);
        Map<String, List<UUID>> byPublicId = rows.stream().collect(Collectors.groupingBy(
                SolutionRow::publicSolutionId, Collectors.mapping(SolutionRow::solutionId, Collectors.toList())));
        List<VerifiedSolutionRef> refs = new ArrayList<>();
        for (SolutionRow row : rows) {
            if (byPublicId.get(row.publicSolutionId()).size() > 1) {
                log.warn("verified solution {} omitted from references: public id is ambiguous in this note",
                        row.solutionId());
                continue;
            }
            solutionRef(row, revisesId(row, byPublicId)).ifPresent(refs::add);
        }
        return newest(refs, MAX_VERIFIED_SOLUTION_REFS);
    }

    /**
     * 정정 대상의 Brain ID. 범위 안의 다른 solution이 같은 ID를 쓰면(자기 자신 포함) Brain이 엉뚱한 풀이를 정정 대상으로
     * 읽을 수 있으므로 정정 관계를 싣지 않는다(fingerprint 밖의 수명주기 정보다).
     */
    private static String revisesId(SolutionRow row, Map<String, List<UUID>> byPublicId) {
        String revises = row.revisesPublicSolutionId();
        if (revises == null) {
            return null;
        }
        List<UUID> sameId = byPublicId.getOrDefault(revises, List.of());
        if (revises.equals(row.publicSolutionId()) || sameId.stream().anyMatch(id -> !id.equals(
                row.revisesSolutionId()))) {
            log.warn("verified solution {} sent without its revision link: revised public id is ambiguous",
                    row.solutionId());
            return null;
        }
        return revises;
    }

    private static Optional<VerifiedSolutionRef> solutionRef(SolutionRow row, String revises) {
        try {
            List<VerifiedSolutionRefClaim> claims = new ArrayList<>();
            for (JsonNode claim : row.claims()) {
                if (claims.size() == MAX_REF_CLAIMS) {
                    break;
                }
                claims.add(new VerifiedSolutionRefClaim(claim.path("claim_id").asText(null),
                        clip(claim.path("statement").asText(null), MAX_CLAIM_STATEMENT_CHARS),
                        coverage(claim.path("coverage").asText(null))));
            }
            return Optional.of(new VerifiedSolutionRef(row.publicSolutionId(), row.assistantMessageId().toString(),
                    row.superseded() ? SolutionReferenceStatus.SUPERSEDED : SolutionReferenceStatus.ACTIVE,
                    revises, clip(row.problemStatement(), MAX_PROBLEM_SUMMARY_CHARS), claims));
        } catch (InternalRunContractViolation e) {
            log.warn("verified solution {} omitted from references: not representable ({})", row.solutionId(),
                    e.getMessage());
            return Optional.empty();
        }
    }

    private List<TurnAttachmentRef> attachmentRefs(long noteId, HistoryBound.Result history) {
        List<UUID> userIds = history.messages().stream()
                .filter(message -> message.role() == HistoryRole.USER)
                .map(message -> UUID.fromString(message.messageId())).toList();
        List<TurnAttachmentRef> refs = new ArrayList<>();
        for (AttachmentRow row : sources.attachmentsForMessages(noteId, userIds)) {
            Optional<AssetMime> mime = StoredRunAssetFacts.mime(row.mimeType());
            try {
                refs.add(new TurnAttachmentRef(row.attachmentId().toString(), row.sourceMessageId().toString(),
                        row.sha256(), mime.orElse(null), row.pageUnderstandingVersion(),
                        row.imageNormalizationVersion(), row.displayLabel()));
            } catch (InternalRunContractViolation e) {
                log.warn("turn attachment {} omitted from references: not representable ({})", row.attachmentId(),
                        e.getMessage());
            }
        }
        return newest(refs, MAX_TURN_ATTACHMENT_REFS);
    }

    private void requireCurrentAttachments(List<UUID> requested, List<CurrentAttachment> resolved) {
        List<UUID> resolvedIds = resolved.stream().map(CurrentAttachment::attachmentId).toList();
        if (!resolvedIds.equals(requested)) {
            throw RunSnapshotRejectedException.referenceUnavailable();
        }
        RunSnapshotProperties.TurnAttachmentPolicy policy = properties.turnAttachments();
        long total = 0;
        for (CurrentAttachment attachment : resolved) {
            total += attachment.byteSize();
            boolean withinItem = attachment.mime().isPdf()
                    ? attachment.byteSize() <= policy.maxPdfSize().toBytes()
                    && attachment.pageCount() != null && attachment.pageCount() <= policy.maxPdfPages()
                    : attachment.byteSize() <= policy.maxImageSize().toBytes()
                    && attachment.pixelCount() != null && attachment.pixelCount() <= policy.maxImagePixels();
            if (!withinItem) {
                throw RunSnapshotRejectedException.referenceUnavailable();
            }
        }
        if (resolved.size() > policy.maxCount() || total > policy.maxTotalSize().toBytes()) {
            throw RunSnapshotRejectedException.referenceUnavailable();
        }
    }

    private static TurnAttachment unissued(CurrentAttachment attachment) {
        return new TurnAttachment(attachment.attachmentId().toString(), attachment.sha256(), attachment.mime(),
                attachment.byteSize(), attachment.pageCount(), attachment.pixelWidth(), attachment.pixelHeight(),
                attachment.pixelCount(), attachment.uploadPolicyVersion(), attachment.pageUnderstandingVersion(),
                attachment.imageNormalizationVersion(), UNISSUED_READ_URL);
    }

    /**
     * 전송 시점 ACTIVE asset 집합의 version. 집합이나 content version이 바뀌면 달라지고, 같은 집합이면 같다.
     * 빈 Note Corpus도 고정 값을 가진다.
     */
    static String corpusVersion(List<CorpusAsset> corpus) {
        List<Map<String, Object>> members = corpus.stream()
                .sorted((a, b) -> Long.compare(a.assetId(), b.assetId()))
                .map(asset -> Map.<String, Object>of("asset_id", asset.assetId(),
                        "content_version", asset.contentVersion()))
                .toList();
        return "corpus-" + HashUtils.sha256(CanonicalJson.write(members));
    }

    private ObjectNode policySnapshot(ExecutionLimits limits, HistoryBound.Result history) {
        JsonNodeFactory json = JsonNodeFactory.instance;
        ObjectNode policy = json.objectNode();
        policy.put("snapshot_version", SNAPSHOT_VERSION);
        policy.put("contract_version", InternalRunContract.CONTRACT_VERSION);
        policy.put("consumer_version", InternalRunContract.CONSUMER_VERSION);
        policy.put("locale", properties.locale());
        policy.put("budget_policy_version", properties.budget().policyVersion());
        policy.set("limits", InternalRunWire.tree(limits));
        policy.put("request_timeout", properties.requestTimeout().toString());
        ObjectNode historyPolicy = policy.putObject("history");
        historyPolicy.put("max_tokens", properties.historyMaxTokens());
        historyPolicy.put("estimator", HISTORY_ESTIMATOR);
        historyPolicy.put("messages", history.messages().size());
        historyPolicy.put("estimated_tokens", history.estimatedTokens());
        historyPolicy.put("truncated", history.truncated());
        RunSnapshotProperties.Retrieval retrieval = properties.retrieval();
        ObjectNode retrievalPolicy = policy.putObject("retrieval");
        retrievalPolicy.put("search_extraction_version", retrieval.searchExtractionVersion());
        retrievalPolicy.put("chunking_version", retrieval.chunkingVersion());
        retrievalPolicy.put("embedding_version", retrieval.embeddingVersion());
        RunSnapshotProperties.AssetVersions versions = properties.assetVersions();
        ObjectNode assetPolicy = policy.putObject("asset_versions");
        assetPolicy.put("upload_policy_version", versions.uploadPolicyVersion());
        assetPolicy.put("page_understanding_version", versions.pageUnderstandingVersion());
        assetPolicy.put("image_normalization_version", versions.imageNormalizationVersion());
        RunSnapshotProperties.TurnAttachmentPolicy attachments = properties.turnAttachments();
        ObjectNode attachmentPolicy = policy.putObject("turn_attachments");
        attachmentPolicy.put("max_count", attachments.maxCount());
        attachmentPolicy.put("max_total_bytes", attachments.maxTotalSize().toBytes());
        attachmentPolicy.put("max_pdf_bytes", attachments.maxPdfSize().toBytes());
        attachmentPolicy.put("max_pdf_pages", attachments.maxPdfPages());
        attachmentPolicy.put("max_image_bytes", attachments.maxImageSize().toBytes());
        attachmentPolicy.put("max_image_pixels", attachments.maxImagePixels());
        return policy;
    }

    private static ObjectNode viewerSnapshot(ObjectNode requestSnapshot, ViewerAsset viewer) {
        if (viewer == null) {
            return null;
        }
        ObjectNode snapshot = ((ObjectNode) requestSnapshot.get("viewer_context")).deepCopy();
        snapshot.put("content_version", viewer.contentVersion());
        return snapshot;
    }

    private static ObjectNode corpusSnapshot(RetrievalScope scope, List<CorpusAsset> corpus) {
        ObjectNode snapshot = (ObjectNode) InternalRunWire.tree(scope);
        var assets = snapshot.putArray("assets");
        corpus.forEach(asset -> assets.addObject()
                .put("asset_id", asset.assetId())
                .put("content_version", asset.contentVersion()));
        return snapshot;
    }

    private static SummaryCoverage coverage(String wire) {
        return Arrays.stream(SummaryCoverage.values()).filter(c -> c.wireValue().equals(wire)).findFirst()
                .orElse(null);
    }

    /** strip 후 code point 기준으로 앞에서 자른다. 빈 값은 그대로 두어 생성자가 거부하게 한다. */
    static String clip(String value, int maxCodePoints) {
        if (value == null) {
            return null;
        }
        String stripped = BrowserRunContract.strip(value);
        if (stripped.codePointCount(0, stripped.length()) <= maxCodePoints) {
            return stripped;
        }
        return stripped.substring(0, stripped.offsetByCodePoints(0, maxCodePoints));
    }

    /** 생성 순서 목록에서 최신 {@code max}개(순서 유지). */
    private static <T> List<T> newest(List<T> values, int max) {
        return values.size() <= max ? List.copyOf(values) : List.copyOf(values.subList(values.size() - max,
                values.size()));
    }

}
