package com.proovy.domain.run.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proovy.domain.asset.entity.AssetStatus;
import com.proovy.domain.run.brain.SourceEvent;
import com.proovy.domain.run.brain.StoredTerminal;
import com.proovy.domain.run.brain.StoredTerminalType;
import com.proovy.domain.run.brain.SummaryCoverage;
import com.proovy.domain.run.contract.CanonicalJson;
import com.proovy.domain.run.entity.ResponseDisposition;
import com.proovy.domain.run.entity.RunFailureCode;
import com.proovy.domain.run.terminal.TerminalModel.Artifact;
import com.proovy.domain.run.terminal.TerminalModel.Badge;
import com.proovy.domain.run.terminal.TerminalModel.Block;
import com.proovy.domain.run.terminal.TerminalModel.BlockKind;
import com.proovy.domain.run.terminal.TerminalModel.ClaimSummary;
import com.proovy.domain.run.terminal.TerminalModel.Completed;
import com.proovy.domain.run.terminal.TerminalModel.Existing;
import com.proovy.domain.run.terminal.TerminalModel.Failed;
import com.proovy.domain.run.terminal.TerminalModel.Label;
import com.proovy.domain.run.terminal.TerminalModel.Mark;
import com.proovy.domain.run.terminal.TerminalModel.Page;
import com.proovy.domain.run.terminal.TerminalModel.Response;
import com.proovy.domain.run.terminal.TerminalModel.Selected;
import com.proovy.domain.run.terminal.TerminalModel.SourceRef;
import com.proovy.domain.run.terminal.TerminalModel.Summary;
import com.proovy.domain.solution.entity.VerifiedSolutionStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Brain terminal({@code run.completed}·{@code run.failed})을 Server 기준으로 수락하거나 {@link TerminalRejection}으로 거부한다.
 * <p>
 * 검사 두 층:
 * <ol>
 *   <li>Brain terminal-v2 구조 규칙(Brain {@code RunCompletedPayload}·{@code RunFailedPayload}와 같은 판정):
 *       closed model, SafeMarkdown, page/block/ID, step mark·배지·summary·artifact 정합, disposition 경계,
 *       source catalog·label, provenance manifest와 인용 범위.</li>
 *   <li>Server 소유 규칙: 현재 run·fence, source asset의 run dependency·user/Note·ACTIVE·content version·page 범위,
 *       provenance version·sha256·understanding version, 기존 solution 참조·정정 대상의 승인 범위와 순환·중복 정정,
 *       그리고 Brain보다 엄격한 규칙 두 가지 — 새 Verified Solution은 검증 배지 solution block이 공개해야 하고,
 *       공개 텍스트에 signed URL 흔적이 없어야 한다.</li>
 * </ol>
 * 인용 의미·수학 정확성은 판정하지 않는다(Brain 책임). 판정은 사전 조회 scope 기준이라 저장 transaction이
 * {@link TerminalRecheck}를 다시 확인해야 한다. 저장은 하지 않는다.
 */
public final class TerminalAcceptance {

    private static final ObjectMapper JSON = new ObjectMapper();
    /**
     * 공개 텍스트에 나오면 안 되는 signed URL·임시 자격증명 query 흔적(GCS V2·V4, S3 SigV2·SigV4·STS token).
     * 운영 저장소는 GCS이고 나머지는 같은 종류의 누출을 막는 방어다. 자연어 secret 일반 탐지가 아니다.
     */
    private static final Pattern SIGNED_URL = Pattern.compile(
            "x-goog-signature|x-goog-credential|googleaccessid=|x-amz-signature|x-amz-credential"
                    + "|x-amz-security-token|awsaccesskeyid=",
            Pattern.CASE_INSENSITIVE);

    private TerminalAcceptance() {
    }

    public static AcceptedTerminal accept(StoredTerminal terminal, TerminalAcceptanceScope scope) {
        if (terminal.type() == StoredTerminalType.RUN_CANCELED) {
            throw new TerminalRejection(TerminalRejectionReason.NOT_PUBLICATION_TERMINAL, "type",
                    "run.canceled is settled by the cancellation path");
        }
        return accept(terminal.runId(), terminal.attempt(), terminal.fenceToken(), terminal.sourceEventId(),
                terminal.type() == StoredTerminalType.RUN_COMPLETED, terminal.payload(), scope);
    }

    public static AcceptedTerminal accept(SourceEvent event, TerminalAcceptanceScope scope) {
        if (!event.isTerminal()) {
            throw new TerminalRejection(TerminalRejectionReason.NOT_PUBLICATION_TERMINAL, "type",
                    "activity events are not terminals");
        }
        return accept(event.runId(), event.attempt(), event.fenceToken(), event.sourceEventId(),
                event.type() == com.proovy.domain.run.brain.SourceEventType.RUN_COMPLETED, event.payload(), scope);
    }

    private static AcceptedTerminal accept(String runId, int attempt, String fenceToken, String sourceEventId,
            boolean completed, JsonNode payload, TerminalAcceptanceScope scope) {
        if (!scope.runId().equals(runId) || scope.attempt() != attempt || !scope.fenceToken().equals(fenceToken)) {
            throw new TerminalRejection(TerminalRejectionReason.RUN_MISMATCH, "envelope",
                    "terminal is not from the current run attempt and fence");
        }
        if (!completed) {
            Failed failed = Failed.parse(payload);
            checkFailure(failed);
            return new AcceptedFailure(runId, attempt, fenceToken, sourceEventId, failed.code(),
                    failed.failureClass(), failed.reason(), failed.message(), failed.recoverable());
        }
        Completed payloadModel = Completed.parse(payload);
        checkCompleted(payloadModel);
        checkPublished(payloadModel);
        checkForbiddenContent(payloadModel);
        Map<String, ScopedAsset> citedAssets = checkSourceScope(payloadModel, scope);
        List<UUID> referenced = checkExistingReferences(payloadModel, scope);
        Map<String, UUID> revisions = checkRevisions(payloadModel, scope);
        return build(runId, attempt, fenceToken, sourceEventId, payloadModel, citedAssets, referenced, revisions);
    }

    // ---- run.failed -------------------------------------------------------------------------------------

    private static void checkFailure(Failed failed) {
        if (failed.code() == RunFailureCode.INVALID_ATTACHMENT) {
            if (failed.reason() == null || failed.recoverable()) {
                throw failure("invalid_attachment requires a reason and is never retryable");
            }
        } else if (failed.reason() != null) {
            throw failure("only invalid_attachment carries a reason");
        }
        if (!failed.message().equals(PublicWording.failureMessage(failed.code(), failed.reason()))
                || failed.failureClass() != PublicWording.failureClass(failed.code())) {
            throw failure("code, class and fixed safe message must agree");
        }
    }

    // ---- run.completed: Brain terminal-v2 규칙 ---------------------------------------------------------------

    /** Brain {@code RunCompletedPayload.validate_completed}. */
    private static void checkCompleted(Completed payload) {
        Response response = payload.response();
        checkDisposition(payload);
        checkPublicReferences(response, payload.summaries());
        Map<String, Artifact> artifacts = new LinkedHashMap<>();
        payload.artifacts().forEach(artifact -> artifacts.put(artifact.solutionId(), artifact));
        TerminalModel.unique(payload.artifacts(), Artifact::solutionId, "payload.verified_solutions");
        Map<String, Existing> existing = new LinkedHashMap<>();
        payload.existingOrEmpty().forEach(reference -> existing.put(reference.solutionId(), reference));
        TerminalModel.unique(payload.existingOrEmpty(), Existing::solutionId, "payload.existing_solution_references");
        if (existing.keySet().stream().anyMatch(artifacts::containsKey)) {
            throw verification("payload", "new artifacts and existing references must remain distinct");
        }
        Set<String> owners = new HashSet<>(artifacts.keySet());
        owners.addAll(existing.keySet());
        Set<String> summarized = new HashSet<>();
        payload.summaries().forEach(summary -> summarized.add(summary.solutionId()));
        if (!summarized.equals(owners)) {
            throw verification("payload.verification_summary", "summaries require artifacts or existing references");
        }
        for (Summary summary : payload.summaries()) {
            Summary owner = artifacts.containsKey(summary.solutionId())
                    ? artifacts.get(summary.solutionId()).verification()
                    : existing.get(summary.solutionId()).verification();
            if (!owner.equals(summary)) {
                throw verification("payload.verification_summary", "artifact and public coverage must agree");
            }
        }
        Set<String> usedExisting = new HashSet<>();
        for (Block block : response.blocks()) {
            if (block.kind() == BlockKind.EXPLANATION && existing.containsKey(block.solutionId())) {
                usedExisting.add(block.solutionId());
            }
            if (block.kind() == BlockKind.SOLUTION && block.solutionId() != null) {
                Artifact artifact = artifacts.get(block.solutionId());
                if (artifact == null) {
                    throw verification("payload.assistant_response", "new solution blocks require new artifacts");
                }
                Map<String, Mark> artifactMarks = new HashMap<>();
                artifact.marks().forEach(mark -> artifactMarks.put(mark.stepId(), mark));
                boolean agree = block.marks().size() == artifactMarks.size() && block.marks().stream()
                        .allMatch(mark -> artifactMarks.containsKey(mark.stepId())
                                && artifactMarks.get(mark.stepId()).status() == mark.status()
                                && Objects.equals(artifactMarks.get(mark.stepId()).reason(), mark.reason()));
                if (!agree) {
                    throw verification("payload.assistant_response", "public and artifact step states must agree");
                }
            }
        }
        if (!usedExisting.equals(existing.keySet())) {
            throw verification("payload.existing_solution_references", "existing references require re-expressions");
        }
        Set<String> catalog = new LinkedHashSet<>();
        response.refs().forEach(ref -> catalog.add(ref.sourceRefId()));
        TerminalModel.unique(payload.labels(), Label::sourceRefId, "payload.source_labels");
        Set<String> labelled = new HashSet<>();
        payload.labels().forEach(label -> labelled.add(label.sourceRefId()));
        if (!labelled.equals(catalog)) {
            throw catalog("payload.source_labels", "all source labels must match the catalog");
        }
        if (payload.artifacts().stream().anyMatch(artifact -> !catalog.containsAll(artifact.sourceRefIds()))) {
            throw catalog("payload.verified_solutions", "artifact sources must name catalog entries");
        }
        checkProvenance(payload);
        if (payload.disposition() != ResponseDisposition.SUBSTANTIVE
                && (!payload.artifacts().isEmpty() || !payload.selected().isEmpty())) {
            throw disposition("guidance cannot carry internal answer artifacts");
        }
    }

    /** Brain {@code _check_disposition}: clarification·unsupported는 rich_text 하나뿐이고 출처·검증이 없다. */
    private static void checkDisposition(Completed payload) {
        ResponseDisposition disposition = payload.disposition();
        if ((disposition == ResponseDisposition.CLARIFICATION_ONLY) != (payload.clarificationReason() != null)) {
            throw disposition("only clarification carries a clarification reason");
        }
        if (disposition == ResponseDisposition.SUBSTANTIVE) {
            return;
        }
        List<Block> blocks = payload.response().blocks();
        if (!payload.response().refs().isEmpty() || !payload.summaries().isEmpty()
                || payload.response().pages().size() != 1 || blocks.size() != 1
                || blocks.get(0).kind() != BlockKind.RICH_TEXT) {
            throw disposition("guidance has only one rich text block and no provenance");
        }
        if (disposition == ResponseDisposition.UNSUPPORTED_ONLY
                && !blocks.get(0).body().equals(PublicWording.UNSUPPORTED_RESPONSE_BODY)) {
            throw disposition("unsupported guidance has a fixed body");
        }
    }

    /** Brain {@code _check_public_references}. */
    private static void checkPublicReferences(Response response, List<Summary> summaries) {
        TerminalModel.unique(summaries, Summary::solutionId, "payload.verification_summary");
        Map<String, Summary> catalog = new HashMap<>();
        for (Summary summary : summaries) {
            summary.checkLabels("payload.verification_summary");
            catalog.put(summary.solutionId(), summary);
        }
        for (Block block : response.blocks()) {
            if (block.marks().stream().anyMatch(mark -> mark.reason() != null
                    && !PublicWording.STEP_REASONS.contains(mark.reason()))) {
                throw verification("payload.assistant_response", "step reasons require public wording");
            }
            if (block.solutionId() == null) {
                continue;
            }
            Summary summary = catalog.get(block.solutionId());
            Set<String> claimIds = new HashSet<>();
            if (summary != null) {
                summary.claims().forEach(claim -> claimIds.add(claim.claimId()));
            }
            if (summary == null || !claimIds.containsAll(block.claimIds())) {
                throw verification("payload.assistant_response", "solution and claim references require verification");
            }
            if (block.kind() == BlockKind.SOLUTION) {
                Badge expected = summary.overall() == SummaryCoverage.DIRECT ? Badge.VERIFIED : Badge.PARTIALLY_VERIFIED;
                if (block.badge() != expected) {
                    throw verification("payload.assistant_response", "verification badge must match coverage");
                }
                Map<String, String> unconfirmed = TerminalModel.unconfirmedMarks(block.marks());
                for (ClaimSummary claim : summary.claims()) {
                    if (!claim.unconfirmedByStep().equals(unconfirmed)) {
                        throw verification("payload.assistant_response", "unconfirmed steps must match verification");
                    }
                }
            }
        }
    }

    /** selected provenance: (asset, page) unique, asset별 version 일치, 인용 범위의 모든 page 포함, 범위 밖 page 없음. */
    private static void checkProvenance(Completed payload) {
        Map<String, Map<Integer, Selected>> pages = new HashMap<>();
        Map<String, List<String>> versions = new HashMap<>();
        for (Selected item : payload.selected()) {
            if (pages.computeIfAbsent(item.assetId(), key -> new HashMap<>()).put(item.page(), item) != null) {
                throw provenance("selected provenance pages must be unique");
            }
            List<String> version = List.of(item.contentVersion(), item.sha256(), item.understandingVersion());
            if (!versions.computeIfAbsent(item.assetId(), key -> version).equals(version)) {
                throw provenance("selected asset versions must agree");
            }
        }
        List<SourceRef> refs = payload.response().refs();
        for (SourceRef ref : refs) {
            Map<Integer, Selected> assetPages = pages.getOrDefault(ref.assetId(), Map.of());
            for (int page = ref.pageStart(); page <= ref.pageEnd(); page++) {
                if (!assetPages.containsKey(page)) {
                    // 비연속 근거를 한 범위로 합치면 중간 page provenance가 없어 여기서 거부된다.
                    throw provenance("every cited page requires selected provenance");
                }
            }
        }
        for (Selected item : payload.selected()) {
            boolean inside = refs.stream().anyMatch(ref -> ref.assetId().equals(item.assetId())
                    && ref.pageStart() <= item.page() && item.page() <= ref.pageEnd());
            if (!inside) {
                throw provenance("selected provenance must stay within source ranges");
            }
        }
    }

    // ---- run.completed: Server 규칙 -----------------------------------------------------------------------

    /** 새 Verified Solution은 검증 배지(verified·partially_verified) solution block이 공개해야 한다(Brain보다 엄격). */
    private static void checkPublished(Completed payload) {
        Set<String> published = new HashSet<>();
        for (Block block : payload.response().blocks()) {
            if (block.kind() == BlockKind.SOLUTION && block.badge() != Badge.UNVERIFIED && block.solutionId() != null) {
                published.add(block.solutionId());
            }
        }
        for (Artifact artifact : payload.artifacts()) {
            if (!published.contains(artifact.solutionId())) {
                throw verification("payload.verified_solutions",
                        "a Verified Solution must be published by a verified solution block");
            }
        }
    }

    private static void checkForbiddenContent(Completed payload) {
        List<String> texts = new ArrayList<>();
        for (Page page : payload.response().pages()) {
            if (page.title() != null) {
                texts.add(page.title());
            }
            page.blocks().forEach(block -> texts.add(block.body()));
        }
        payload.labels().forEach(label -> texts.add(label.label()));
        payload.artifacts().forEach(artifact -> texts.addAll(artifact.texts()));
        for (String text : texts) {
            if (SIGNED_URL.matcher(text).find()) {
                throw new TerminalRejection(TerminalRejectionReason.FORBIDDEN_CONTENT, "payload",
                        "public text cannot carry signed URLs or grants");
            }
        }
    }

    /** source reference·provenance asset이 run dependency 안의 같은 user/Note ACTIVE asset이고 version·page가 맞는지. */
    private static Map<String, ScopedAsset> checkSourceScope(Completed payload, TerminalAcceptanceScope scope) {
        Map<String, ScopedAsset> approved = scope.assetsByBrainId();
        Map<String, ScopedAsset> cited = new LinkedHashMap<>();
        for (SourceRef ref : payload.response().refs()) {
            ScopedAsset asset = scopedAsset(approved, ref.assetId(), scope);
            if (ref.pageEnd() > asset.lastPage()) {
                throw scopeViolation("cited pages must exist in the asset");
            }
            cited.put(ref.assetId(), asset);
        }
        for (Selected item : payload.selected()) {
            ScopedAsset asset = scopedAsset(approved, item.assetId(), scope);
            if (!item.contentVersion().equals(asset.brainContentVersion()) || !item.sha256().equals(asset.sha256())
                    || !item.understandingVersion().equals(asset.pageUnderstandingVersion())) {
                throw scopeViolation("provenance must match the run's asset version");
            }
            if (item.page() > asset.lastPage()) {
                throw scopeViolation("provenance pages must exist in the asset");
            }
        }
        return cited;
    }

    private static ScopedAsset scopedAsset(Map<String, ScopedAsset> approved, String brainAssetId,
            TerminalAcceptanceScope scope) {
        ScopedAsset asset = approved.get(brainAssetId);
        if (asset == null) {
            throw scopeViolation("source asset is not a dependency of this run");
        }
        if (asset.ownerUserId() != scope.userId() || asset.noteId() != scope.noteId()) {
            throw scopeViolation("source asset belongs to another user or Note");
        }
        if (asset.status() != AssetStatus.ACTIVE) {
            throw scopeViolation("source asset is not active");
        }
        if (asset.dependencyContentVersion() != asset.currentContentVersion()) {
            throw scopeViolation("source asset content changed after the run started");
        }
        return asset;
    }

    /**
     * existing reference는 run이 보낸 참조 안이고 version·claim coverage가 저장된 row와 같아야 한다.
     * superseded row의 재표현도 허용한다(사용자 결정): 원래 coverage 배지가 따라오므로 "정정된 풀이" 표시는 1.86/S08 화면이 한다.
     */
    private static List<UUID> checkExistingReferences(Completed payload, TerminalAcceptanceScope scope) {
        Map<String, ScopedSolution> approved = scope.solutionsByPublicId();
        List<UUID> referenced = new ArrayList<>();
        for (Existing reference : payload.existingOrEmpty()) {
            ScopedSolution solution = approved.get(reference.solutionId());
            if (solution == null || solution.noteId() != scope.noteId()) {
                throw referenceViolation("existing reference was not approved for this run");
            }
            if (reference.version() != solution.solutionVersion()) {
                throw referenceViolation("existing reference version must match");
            }
            for (ClaimSummary claim : reference.verification().claims()) {
                if (solution.claimCoverage().get(claim.claimId()) != claim.coverage()) {
                    throw referenceViolation("existing claim coverage must match the stored solution");
                }
            }
            referenced.add(solution.solutionId());
        }
        return referenced;
    }

    /** revises_solution_id: 승인 참조 안의 ACTIVE row만, 같은 terminal artifact·중복 정정·기존 이력 순환(데이터 손상)은 거부. */
    private static Map<String, UUID> checkRevisions(Completed payload, TerminalAcceptanceScope scope) {
        Map<String, ScopedSolution> approved = scope.solutionsByPublicId();
        Set<String> newIds = new HashSet<>();
        payload.artifacts().forEach(artifact -> newIds.add(artifact.solutionId()));
        Map<String, UUID> revisions = new HashMap<>();
        Set<UUID> targets = new HashSet<>();
        for (Artifact artifact : payload.artifacts()) {
            String target = artifact.revisesSolutionId();
            if (target == null) {
                continue;
            }
            if (newIds.contains(target)) {
                throw revision("a revision must target an existing solution, not one in the same terminal");
            }
            ScopedSolution solution = approved.get(target);
            if (solution == null || solution.noteId() != scope.noteId()) {
                throw revision("revision target was not approved for this run");
            }
            if (solution.status() != VerifiedSolutionStatus.ACTIVE) {
                throw revision("revision target is already superseded");
            }
            if (!targets.add(solution.solutionId())) {
                throw revision("one solution can be revised only once");
            }
            // 새 artifact는 아직 UUID가 없어 이 terminal이 순환을 만들 수는 없다. 이 loop는 이미 손상된 기존 이력만
            // 탐지한다. 같은 대상의 동시 정정 경합은 1.82의 조건부 UPDATE(WHERE status='ACTIVE')가 막는다.
            Set<UUID> seen = new HashSet<>();
            for (UUID cursor = solution.solutionId(); cursor != null; cursor = scope.revisionLinks().get(cursor)) {
                if (!seen.add(cursor)) {
                    throw revision("revision history must not be cyclic");
                }
            }
            revisions.put(artifact.solutionId(), solution.solutionId());
        }
        return revisions;
    }

    // ---- 저장 입력 ----------------------------------------------------------------------------------------

    private static AcceptedCompletion build(String runId, int attempt, String fenceToken, String sourceEventId,
            Completed payload, Map<String, ScopedAsset> citedAssets, List<UUID> referenced,
            Map<String, UUID> revisions) {
        Response response = payload.response();
        List<Object> pages = response.pages().stream().<Object>map(Page::toMap).toList();
        Map<String, String> labels = new HashMap<>();
        payload.labels().forEach(label -> labels.put(label.sourceRefId(), label.label()));
        List<Object> references = new ArrayList<>();
        for (SourceRef ref : response.refs()) {
            references.add(TerminalModel.map("source_ref_id", ref.sourceRefId(),
                    "asset_id", citedAssets.get(ref.assetId()).noteAssetId(),
                    "page_start", ref.pageStart(), "page_end", ref.pageEnd(),
                    "display_label_snapshot", labels.get(ref.sourceRefId())));
        }
        String plainText = plainText(response);
        String contentSha256 = sha256(TerminalModel.map("response_pages", pages, "source_references", references,
                "plain_text", plainText));
        List<VerifiedSolutionInput> solutions = new ArrayList<>();
        for (Artifact artifact : payload.artifacts()) {
            Map<String, SummaryCoverage> coverage = new HashMap<>();
            artifact.verification().claims().forEach(claim -> coverage.put(claim.claimId(), claim.coverage()));
            List<Object> claims = artifact.claims().stream().<Object>map(claim -> TerminalModel.map(
                    "claim_id", claim.claimId(), "statement", claim.statement(),
                    "coverage", coverage.get(claim.claimId()).wireValue())).toList();
            solutions.add(new VerifiedSolutionInput(artifact.solutionId(), TerminalModel.ARTIFACT_SCHEMA_VERSION, 1,
                    artifact.problemStatement(),
                    tree(artifact.conditions().stream().map(TerminalModel.Condition::toMap).toList()),
                    artifact.solutionText(), tree(claims), tree(artifact.marks().stream().map(Mark::toMap).toList()),
                    tree(artifact.verification().toMap()), TerminalModel.VERIFICATION_VERSION,
                    tree(artifact.sourceRefIds()), artifact.verification().overall(), sha256(artifact.toMap()),
                    revisions.get(artifact.solutionId())));
        }
        List<TerminalRecheck.CitedAsset> recheckAssets = citedAssets.values().stream()
                .map(asset -> new TerminalRecheck.CitedAsset(asset.noteAssetId(), asset.currentContentVersion()))
                .toList();
        return new AcceptedCompletion(runId, attempt, fenceToken, sourceEventId, payload.disposition(),
                payload.clarificationReason(), tree(pages), tree(references), plainText, contentSha256,
                tree(payload.summaries().stream().map(Summary::toMap).toList()), solutions, payload.model(),
                payload.tier().name().toLowerCase(Locale.ROOT),
                new TerminalRecheck(recheckAssets, referenced, List.copyOf(revisions.values())));
    }

    /** Brain {@code derive_plain_text}: page title과 block body(render)만, 빈 부분은 건너뛰고 두 줄바꿈으로 잇는다. */
    static String plainText(Response response) {
        List<String> pages = new ArrayList<>();
        for (Page page : response.pages()) {
            List<String> parts = new ArrayList<>();
            if (page.title() != null) {
                parts.add(page.title());
            }
            page.blocks().forEach(block -> parts.add(SafeMarkdown.renderPlainText(block.body())));
            String joined = String.join("\n\n", parts.stream().filter(part -> !part.isEmpty()).toList());
            if (!joined.isEmpty()) {
                pages.add(joined);
            }
        }
        return String.join("\n\n", pages);
    }

    private static JsonNode tree(Object value) {
        return JSON.valueToTree(value);
    }

    private static String sha256(Object canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(CanonicalJson.write(canonical).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static TerminalRejection verification(String field, String problem) {
        return new TerminalRejection(TerminalRejectionReason.VERIFICATION_INTEGRITY, field, problem);
    }

    private static TerminalRejection catalog(String field, String problem) {
        return new TerminalRejection(TerminalRejectionReason.SOURCE_CATALOG, field, problem);
    }

    private static TerminalRejection disposition(String problem) {
        return new TerminalRejection(TerminalRejectionReason.DISPOSITION_STRUCTURE, "payload", problem);
    }

    private static TerminalRejection provenance(String problem) {
        return new TerminalRejection(TerminalRejectionReason.PROVENANCE, "payload.selected_page_provenance", problem);
    }

    private static TerminalRejection failure(String problem) {
        return new TerminalRejection(TerminalRejectionReason.FAILURE_PAYLOAD, "payload", problem);
    }

    private static TerminalRejection scopeViolation(String problem) {
        return new TerminalRejection(TerminalRejectionReason.SOURCE_SCOPE, "payload", problem);
    }

    private static TerminalRejection referenceViolation(String problem) {
        return new TerminalRejection(TerminalRejectionReason.SOLUTION_REFERENCE_SCOPE,
                "payload.existing_solution_references", problem);
    }

    private static TerminalRejection revision(String problem) {
        return new TerminalRejection(TerminalRejectionReason.REVISION, "payload.verified_solutions", problem);
    }
}
