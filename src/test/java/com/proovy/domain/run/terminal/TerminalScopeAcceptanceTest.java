package com.proovy.domain.run.terminal;

import static com.proovy.domain.run.terminal.TerminalFixtures.NOTE_ID;
import static com.proovy.domain.run.terminal.TerminalFixtures.USER_ID;
import static com.proovy.domain.run.terminal.TerminalFixtures.copy;
import static com.proovy.domain.run.terminal.TerminalFixtures.pair;
import static com.proovy.domain.run.terminal.TerminalFixtures.seededScope;
import static com.proovy.domain.run.terminal.TerminalFixtures.solutionUuid;
import static com.proovy.domain.run.terminal.TerminalFixtures.stored;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proovy.domain.asset.entity.Asset;
import com.proovy.domain.asset.entity.AssetStatus;
import com.proovy.domain.run.brain.SummaryCoverage;
import com.proovy.domain.run.entity.RunAssetDependency;
import com.proovy.domain.run.entity.RunAssetDependencyKind;
import com.proovy.domain.solution.entity.VerifiedSolution;
import com.proovy.domain.solution.entity.VerifiedSolutionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Server 소유 수락 규칙: fixture pair를 한 가지씩 바꿔 거부 범주를 확인한다.
 * seeded scope는 fake 자료이고 실제 S09 업로드·GCS 경로를 거치지 않는다. 출처 검사는 metadata 대조일 뿐
 * 인용 의미·정답(grounding)을 보장하지 않는다.
 */
class TerminalScopeAcceptanceTest {

    private static final String SEEDED = "direct-verified";
    private static final String TEXT_ONLY = "inconclusive-unverified";

    @Nested
    @DisplayName("source 없는 텍스트 결과")
    class TextOnly {

        @Test
        @DisplayName("asset 없는 scope로 수락되고 source snapshot·cited asset이 비어 있다")
        void acceptedWithoutAssets() {
            JsonNode source = pair(TEXT_ONLY).get("source");
            AcceptedCompletion completion = completion(source, TerminalAcceptanceScope.textOnly(
                    source.get("run_id").asText(), 1, source.get("fence_token").asText(), USER_ID, NOTE_ID));
            assertThat(completion.sourceReferences()).isEmpty();
            assertThat(completion.recheck().citedAssets()).isEmpty();
            // inconclusive 공개는 Verified Solution을 만들지 않는다.
            assertThat(completion.verifiedSolutions()).isEmpty();
        }

        @Test
        @DisplayName("텍스트 run 결과가 승인되지 않은 asset을 인용하면 거부된다")
        void sourceWithoutDependencyRejected() {
            ObjectNode source = copy(pair(SEEDED).get("source"));
            assertRejected(source, TerminalAcceptanceScope.textOnly(source.get("run_id").asText(), 1,
                    source.get("fence_token").asText(), USER_ID, NOTE_ID), TerminalRejectionReason.SOURCE_SCOPE);
        }
    }

    @Nested
    @DisplayName("seeded source 결과")
    class SeededSource {

        @Test
        @DisplayName("승인 dependency의 Server asset ID·checked label로 source snapshot을 만들고 재확인 항목을 넘긴다")
        void acceptedWithSnapshot() {
            JsonNode source = pair(SEEDED).get("source");
            AcceptedCompletion completion = completion(source, seededScope(source));
            JsonNode reference = completion.sourceReferences().get(0);
            assertThat(reference.get("asset_id").asLong()).isEqualTo(100L);
            assertThat(reference.get("page_start").asInt()).isEqualTo(3);
            assertThat(reference.get("page_end").asInt()).isEqualTo(4);
            assertThat(reference.get("display_label_snapshot").asText()).isEqualTo("개념 노트 3–4쪽");
            assertThat(completion.recheck().citedAssets()).containsExactly(new TerminalRecheck.CitedAsset(100L, 1));
            // plain text는 label이 아니라 공개 block에서만 파생한다(marker 제거).
            assertThat(completion.plainText()).doesNotContain("[[source:").doesNotContain("개념 노트");
        }

        @Test
        @DisplayName("다른 Note·다른 사용자·DELETING·변경된 content version의 asset은 거부된다")
        void outOfScopeAssetsRejected() {
            JsonNode source = pair(SEEDED).get("source");
            ScopedAsset base = seededScope(source).assets().get(0);
            for (ScopedAsset asset : List.of(
                    withAsset(base, 999L, base.noteId(), AssetStatus.ACTIVE, 1, null),
                    withAsset(base, base.ownerUserId(), 999L, AssetStatus.ACTIVE, 1, null),
                    withAsset(base, base.ownerUserId(), base.noteId(), AssetStatus.DELETING, 1, null),
                    withAsset(base, base.ownerUserId(), base.noteId(), AssetStatus.ACTIVE, 2, null),
                    withAsset(base, base.ownerUserId(), base.noteId(), AssetStatus.ACTIVE, 1, 3))) {
                assertRejected(source, scopeWith(source, List.of(asset), List.of(), Map.of()),
                        TerminalRejectionReason.SOURCE_SCOPE);
            }
        }

        @Test
        @DisplayName("provenance의 content version·sha256·understanding version이 run snapshot과 다르면 거부된다")
        void provenanceVersionMismatchRejected() {
            JsonNode source = pair(SEEDED).get("source");
            ScopedAsset base = seededScope(source).assets().get(0);
            String other = "b".repeat(64);
            for (ScopedAsset asset : List.of(
                    new ScopedAsset(base.brainAssetId(), 100L, USER_ID, NOTE_ID, AssetStatus.ACTIVE, 1, 1,
                            "content-v2", base.sha256(), 20, base.pageUnderstandingVersion()),
                    new ScopedAsset(base.brainAssetId(), 100L, USER_ID, NOTE_ID, AssetStatus.ACTIVE, 1, 1,
                            base.brainContentVersion(), other, 20, base.pageUnderstandingVersion()),
                    new ScopedAsset(base.brainAssetId(), 100L, USER_ID, NOTE_ID, AssetStatus.ACTIVE, 1, 1,
                            base.brainContentVersion(), base.sha256(), 20, "page-understanding-v2"))) {
                assertRejected(source, scopeWith(source, List.of(asset), List.of(), Map.of()),
                        TerminalRejectionReason.SOURCE_SCOPE);
            }
        }

        @Test
        @DisplayName("비연속 근거(3·5쪽)를 3–5쪽 한 범위로 합치면 4쪽 provenance가 없어 거부된다")
        void nonContiguousMergeRejected() {
            ObjectNode source = copy(pair(SEEDED).get("source"));
            ((ObjectNode) source.at("/payload/assistant_response/source_references/0")).put("page_end", 5);
            ((ObjectNode) source.at("/payload/selected_page_provenance/1")).put("page", 5);
            assertRejected(source, seededScope(source), TerminalRejectionReason.PROVENANCE);
        }

        @Test
        @DisplayName("범위 안 page의 provenance 누락·범위 밖 provenance는 거부된다")
        void missingOrStrayProvenanceRejected() {
            ObjectNode missing = copy(pair(SEEDED).get("source"));
            ((ArrayNode) missing.at("/payload/selected_page_provenance")).remove(1);
            assertRejected(missing, seededScope(pair(SEEDED).get("source")), TerminalRejectionReason.PROVENANCE);

            ObjectNode stray = copy(pair(SEEDED).get("source"));
            ((ObjectNode) stray.at("/payload/selected_page_provenance/1")).put("page", 9);
            assertRejected(stray, seededScope(stray), TerminalRejectionReason.PROVENANCE);
        }

        @Test
        @DisplayName("phantom inline marker·catalog에 없는 label은 거부된다")
        void phantomMarkerAndCatalogRejected() {
            ObjectNode marker = copy(pair(SEEDED).get("source"));
            ObjectNode block = (ObjectNode) marker.at("/payload/assistant_response/response_pages/0/result_blocks/0");
            block.put("body", block.get("body").asText() + "[[source:sref-9]]");
            assertRejected(marker, seededScope(marker), TerminalRejectionReason.RESPONSE_INTEGRITY);

            ObjectNode label = copy(pair(SEEDED).get("source"));
            ((ArrayNode) label.at("/payload/source_labels")).addObject()
                    .put("source_ref_id", "sref-2").put("label", "다른 노트 1쪽");
            assertRejected(label, seededScope(label), TerminalRejectionReason.SOURCE_CATALOG);
        }

        @Test
        @DisplayName("공개 본문에 signed URL이 있으면 거부된다")
        void signedUrlRejected() {
            ObjectNode source = copy(pair(SEEDED).get("source"));
            ObjectNode block = (ObjectNode) source.at("/payload/assistant_response/response_pages/0/result_blocks/0");
            block.put("body", block.get("body").asText()
                    + " [원본](https://storage.googleapis.com/b/o?X-Goog-Signature=abc)");
            assertRejected(source, seededScope(source), TerminalRejectionReason.FORBIDDEN_CONTENT);
        }

        @Test
        @DisplayName("S3 SigV2·STS token 흔적도 signed URL로 거부된다")
        void s3TemporaryCredentialRejected() {
            for (String query : List.of("AWSAccessKeyId=a&Signature=b", "X-Amz-Security-Token=t")) {
                ObjectNode source = copy(pair(SEEDED).get("source"));
                ObjectNode block = (ObjectNode) source.at("/payload/assistant_response/response_pages/0/result_blocks/0");
                block.put("body", block.get("body").asText() + " [원본](https://s3.amazonaws.com/b/o?" + query + ")");
                assertRejected(source, seededScope(source), TerminalRejectionReason.FORBIDDEN_CONTENT);
            }
        }

        @Test
        @DisplayName("page metadata 없는 PDF dependency로는 scope를 만들 수 없다")
        void pdfWithoutPageCountRefused() {
            Asset asset = Asset.builder().userId(USER_ID).noteId(NOTE_ID).fileName("a.pdf").fileSize(10L)
                    .mimeType("application/pdf").objectKey("k").source(Asset.AssetSource.upload)
                    .status(AssetStatus.ACTIVE).build();
            ReflectionTestUtils.setField(asset, "id", 100L);
            RunAssetDependency dependency = new RunAssetDependency(UUID.randomUUID(), NOTE_ID, 100L,
                    RunAssetDependencyKind.CORPUS_SNAPSHOT, 1, Instant.now());
            assertThatThrownBy(() -> ScopedAsset.of(dependency, asset, "note-alpha", "content-v1", "pu-v1"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("dependency·asset entity와 snapshot wire 값으로 scope를 만든다")
        void scopedAssetFromEntities() {
            UUID runId = UUID.randomUUID();
            Asset asset = Asset.builder().userId(USER_ID).noteId(NOTE_ID).fileName("a.pdf").fileSize(10L)
                    .mimeType("application/pdf").objectKey("k").source(Asset.AssetSource.upload)
                    .status(AssetStatus.ACTIVE).build();
            ReflectionTestUtils.setField(asset, "id", 100L);
            ReflectionTestUtils.setField(asset, "sha256", "a".repeat(64));
            ReflectionTestUtils.setField(asset, "pageCount", 20);
            RunAssetDependency dependency = new RunAssetDependency(runId, NOTE_ID, 100L,
                    RunAssetDependencyKind.CORPUS_SNAPSHOT, 1, Instant.now());
            JsonNode source = pair(SEEDED).get("source");
            ScopedAsset scoped = ScopedAsset.of(dependency, asset, "note-alpha", "content-v1", "page-understanding-v1");
            assertThat(scoped).isEqualTo(seededScope(source).assets().get(0));
            completion(source, scopeWith(source, List.of(scoped), List.of(), Map.of()));
        }
    }

    @Nested
    @DisplayName("disposition 경계")
    class Disposition {

        @Test
        @DisplayName("혼합 substantive를 clarification_only로 바꾸면(무료 처리) 거부된다")
        void mixedSubstantiveAsClarificationRejected() {
            ObjectNode source = copy(pair("mixed-substantive-notice").get("source"));
            ((ObjectNode) source.get("payload")).put("response_disposition", "clarification_only")
                    .put("clarification_reason", "missing_input");
            assertRejected(source, seededScope(source), TerminalRejectionReason.DISPOSITION_STRUCTURE);
        }

        @Test
        @DisplayName("clarification에 solution block·provenance·source가 섞이거나 reason이 없으면 거부된다")
        void clarificationStructureRejected() {
            ObjectNode extraBlock = copy(pair("clarification").get("source"));
            ArrayNode blocks = (ArrayNode) extraBlock.at("/payload/assistant_response/response_pages/0/result_blocks");
            blocks.addObject().put("block_id", "block-9").put("kind", "notice").put("body", "추가 안내다.");
            assertRejected(extraBlock, seededScope(extraBlock), TerminalRejectionReason.DISPOSITION_STRUCTURE);

            ObjectNode noReason = copy(pair("clarification").get("source"));
            ((ObjectNode) noReason.get("payload")).remove("clarification_reason");
            assertRejected(noReason, seededScope(noReason), TerminalRejectionReason.DISPOSITION_STRUCTURE);

            ObjectNode substantiveWithReason = copy(pair(TEXT_ONLY).get("source"));
            ((ObjectNode) substantiveWithReason.get("payload")).put("clarification_reason", "missing_input");
            assertRejected(substantiveWithReason, seededScope(substantiveWithReason),
                    TerminalRejectionReason.DISPOSITION_STRUCTURE);
        }

        @Test
        @DisplayName("unsupported_only는 고정 안내 본문만 허용한다")
        void unsupportedBodyFixed() {
            ObjectNode source = copy(pair("unsupported-only").get("source"));
            ((ObjectNode) source.at("/payload/assistant_response/response_pages/0/result_blocks/0"))
                    .put("body", "변형 문제: $x+1=3$을 풀어라.");
            assertRejected(source, seededScope(source), TerminalRejectionReason.DISPOSITION_STRUCTURE);
        }

        @Test
        @DisplayName("clarification_only·unsupported_only 정상 pair는 빈 source·solution·summary로 수락된다")
        void guidanceAccepted() {
            for (String name : List.of("clarification", "unsupported-only", "conflict-clarification")) {
                AcceptedCompletion completion = completion(pair(name).get("source"), seededScope(pair(name).get("source")));
                assertThat(completion.sourceReferences()).isEmpty();
                assertThat(completion.verifiedSolutions()).isEmpty();
                assertThat(completion.verificationSummary()).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("Brain parity: enum·Literal은 strip하지 않는다")
    class EnumWhitespace {

        @Test
        @DisplayName("공백이 붙은 coverage·tier·disposition·schema_version은 거부된다")
        void paddedEnumRejected() {
            ObjectNode coverage = copy(pair(SEEDED).get("source"));
            ((ObjectNode) coverage.at("/payload/verification_summary/0/claims/0")).put("coverage", "direct ");
            ObjectNode tier = copy(pair(SEEDED).get("source"));
            ((ObjectNode) tier.get("payload")).put("tier", " main");
            ObjectNode disposition = copy(pair(SEEDED).get("source"));
            ((ObjectNode) disposition.get("payload")).put("response_disposition", "substantive ");
            ObjectNode literal = copy(pair(SEEDED).get("source"));
            ((ObjectNode) literal.at("/payload/assistant_response")).put("schema_version", " assistant-response-v1");
            for (ObjectNode source : List.of(coverage, tier, disposition, literal)) {
                assertRejected(source, seededScope(source), TerminalRejectionReason.MALFORMED);
            }
        }
    }

    @Nested
    @DisplayName("Verified Solution insert")
    class VerifiedSolutions {

        @Test
        @DisplayName("direct·supporting coverage를 claim별로 보존한다")
        void coveragePreserved() {
            VerifiedSolutionInput direct = completion(pair(SEEDED).get("source"), seededScope(pair(SEEDED).get("source")))
                    .verifiedSolutions().get(0);
            assertThat(direct.overallCoverage()).isEqualTo(SummaryCoverage.DIRECT);
            assertThat(direct.claims().get(0).get("coverage").asText()).isEqualTo("direct");
            JsonNode supportingSource = pair("supporting-logical-move").get("source");
            VerifiedSolutionInput supporting = completion(supportingSource, seededScope(supportingSource))
                    .verifiedSolutions().get(0);
            assertThat(supporting.overallCoverage()).isEqualTo(SummaryCoverage.SUPPORTING);
            assertThat(supporting.claims()).allSatisfy(claim ->
                    assertThat(claim.get("coverage").asText()).isEqualTo("supporting"));
        }

        @Test
        @DisplayName("inconclusive(unverified) 공개에 artifact가 따라오면 거부된다(Brain보다 엄격)")
        void unverifiedPublicationWithArtifactRejected() {
            ObjectNode source = copy(pair(SEEDED).get("source"));
            ObjectNode block = (ObjectNode) source.at("/payload/assistant_response/response_pages/0/result_blocks/0");
            block.put("verification", "unverified");
            block.remove("solution_id");
            block.putArray("claim_ids");
            assertRejected(source, seededScope(source), TerminalRejectionReason.VERIFICATION_INTEGRITY);
        }

        @Test
        @DisplayName("검증 summary 밖 claim(inconclusive)·inconclusive coverage·failed(reject) step이 든 insert는 거부된다")
        void unacceptedClaimsRejected() {
            ObjectNode extraClaim = copy(pair(SEEDED).get("source"));
            ((ArrayNode) extraClaim.at("/payload/verified_solutions/0/claims")).addObject()
                    .put("claim_id", "claim-2").put("statement", "중간 사실이 성립한다.");
            assertRejected(extraClaim, seededScope(extraClaim), TerminalRejectionReason.VERIFICATION_INTEGRITY);

            ObjectNode inconclusive = copy(pair(SEEDED).get("source"));
            ((ObjectNode) inconclusive.at("/payload/verified_solutions/0/verification/claims/0"))
                    .put("coverage", "inconclusive");
            assertRejected(inconclusive, seededScope(inconclusive), TerminalRejectionReason.MALFORMED);

            ObjectNode rejected = copy(pair(SEEDED).get("source"));
            ((ObjectNode) rejected.at("/payload/verified_solutions/0/step_marks/0")).put("status", "failed")
                    .put("reason", "이 단계가 성립하지 않는다고 확인됐다");
            assertRejected(rejected, seededScope(rejected), TerminalRejectionReason.VERIFICATION_INTEGRITY);
        }
    }

    @Nested
    @DisplayName("revision 참조")
    class Revisions {

        private final UUID target = solutionUuid("solution-3");

        @Test
        @DisplayName("승인 참조 안의 ACTIVE solution 정정은 Server UUID로 해석되고 재확인 항목에 남는다")
        void approvedRevisionAccepted() {
            ObjectNode source = revising("solution-3");
            AcceptedCompletion completion = completion(source, scopeWith(source, seededScope(source).assets(),
                    List.of(existing("solution-3", VerifiedSolutionStatus.ACTIVE)), Map.of()));
            assertThat(completion.verifiedSolutions().get(0).revisesSolutionId()).isEqualTo(target);
            assertThat(completion.recheck().revisedSolutions()).containsExactly(target);
        }

        @Test
        @DisplayName("승인되지 않은 대상·superseded 대상·자기 자신·순환 이력은 거부된다")
        void invalidRevisionRejected() {
            ObjectNode unknown = revising("solution-3");
            assertRejected(unknown, seededScope(unknown), TerminalRejectionReason.REVISION);

            ObjectNode superseded = revising("solution-3");
            assertRejected(superseded, scopeWith(superseded, seededScope(superseded).assets(),
                    List.of(existing("solution-3", VerifiedSolutionStatus.SUPERSEDED)), Map.of()),
                    TerminalRejectionReason.REVISION);

            ObjectNode self = revising("solution-1");
            assertRejected(self, seededScope(self), TerminalRejectionReason.REVISION);

            ObjectNode cyclic = revising("solution-3");
            UUID older = UUID.randomUUID();
            assertRejected(cyclic, scopeWith(cyclic, seededScope(cyclic).assets(),
                    List.of(existing("solution-3", VerifiedSolutionStatus.ACTIVE)), Map.of(target, older, older, target)),
                    TerminalRejectionReason.REVISION);
        }

        @Test
        @DisplayName("같은 terminal의 새 artifact를 정정 대상으로 삼거나 한 대상을 두 번 정정하면 거부된다")
        void inTerminalRevisionRejected() {
            ObjectNode sameTerminal = withSecondSolution(copy(pair(SEEDED).get("source")));
            ((ObjectNode) sameTerminal.at("/payload/verified_solutions/0")).put("revises_solution_id", "solution-8");
            ((ObjectNode) sameTerminal.at("/payload/verified_solutions/1")).put("revises_solution_id", "solution-1");
            completionRejected(sameTerminal, List.of(), TerminalRejectionReason.REVISION);

            ObjectNode fork = withSecondSolution(copy(pair(SEEDED).get("source")));
            ((ObjectNode) fork.at("/payload/verified_solutions/0")).put("revises_solution_id", "solution-3");
            ((ObjectNode) fork.at("/payload/verified_solutions/1")).put("revises_solution_id", "solution-3");
            completionRejected(fork, List.of(existing("solution-3", VerifiedSolutionStatus.ACTIVE)),
                    TerminalRejectionReason.REVISION);
        }

        @Test
        @DisplayName("저장된 row에서 scope 참조를 만든다")
        void scopedSolutionFromEntity() {
            VerifiedSolution row = VerifiedSolution.builder().id(target).publicSolutionId("solution-1").noteId(NOTE_ID)
                    .sourceRunId(UUID.randomUUID()).assistantMessageId(UUID.randomUUID())
                    .schemaVersion("verified-solution-v1").solutionVersion(1).problemStatement("p")
                    .problemConditions(TerminalFixtures.RAW.createArrayNode()).solutionText("s")
                    .claims(TerminalFixtures.RAW.createArrayNode().add(TerminalFixtures.RAW.createObjectNode()
                            .put("claim_id", "claim-1").put("statement", "c").put("coverage", "supporting")))
                    .stepMarks(TerminalFixtures.RAW.createArrayNode())
                    .verificationSummary(TerminalFixtures.RAW.createObjectNode()).verificationVersion("v")
                    .sourceRefIds(TerminalFixtures.RAW.createArrayNode()).overallCoverage("supporting")
                    .contentSha256("c".repeat(64)).createdAt(Instant.now()).build();
            assertThat(ScopedSolution.of(row, "solution-3")).isEqualTo(new ScopedSolution("solution-3", target,
                    NOTE_ID, VerifiedSolutionStatus.ACTIVE, null, 1, Map.of("claim-1", SummaryCoverage.SUPPORTING)));
        }

        @Test
        @DisplayName("저장 claim coverage가 손상됐으면 추측하지 않고 IllegalStateException")
        void corruptStoredCoverageSurfaces() {
            for (ObjectNode claim : List.of(
                    TerminalFixtures.RAW.createObjectNode().put("claim_id", "claim-1").put("coverage", "inconclusive"),
                    TerminalFixtures.RAW.createObjectNode().put("claim_id", "claim-1"),
                    TerminalFixtures.RAW.createObjectNode().put("coverage", "direct"))) {
                VerifiedSolution row = VerifiedSolution.builder().id(target).publicSolutionId("solution-1")
                        .noteId(NOTE_ID).sourceRunId(UUID.randomUUID()).assistantMessageId(UUID.randomUUID())
                        .schemaVersion("verified-solution-v1").solutionVersion(1).problemStatement("p")
                        .problemConditions(TerminalFixtures.RAW.createArrayNode()).solutionText("s")
                        .claims(TerminalFixtures.RAW.createArrayNode().add(claim))
                        .stepMarks(TerminalFixtures.RAW.createArrayNode())
                        .verificationSummary(TerminalFixtures.RAW.createObjectNode()).verificationVersion("v")
                        .sourceRefIds(TerminalFixtures.RAW.createArrayNode()).overallCoverage("direct")
                        .contentSha256("c".repeat(64)).createdAt(Instant.now()).build();
                assertThatThrownBy(() -> ScopedSolution.of(row, "solution-3")).isInstanceOf(IllegalStateException.class);
            }
        }

        private ObjectNode revising(String targetId) {
            ObjectNode source = copy(pair(SEEDED).get("source"));
            ((ObjectNode) source.at("/payload/verified_solutions/0")).put("revises_solution_id", targetId);
            return source;
        }

        private void completionRejected(ObjectNode source, List<ScopedSolution> solutions,
                TerminalRejectionReason reason) {
            assertRejected(source, scopeWith(source, seededScope(source).assets(), solutions, Map.of()), reason);
        }

        private ScopedSolution existing(String publicId, VerifiedSolutionStatus status) {
            return new ScopedSolution(publicId, solutionUuid(publicId), NOTE_ID, status, null, 1,
                    Map.of("claim-1", SummaryCoverage.DIRECT));
        }
    }

    @Nested
    @DisplayName("기존 solution 재표현 참조")
    class ExistingReferences {

        @Test
        @DisplayName("승인되지 않은 참조·version·coverage 불일치는 거부된다")
        void referenceScopeRejected() {
            JsonNode source = pair("explain-existing").get("source");
            completion(source, seededScope(source));
            assertRejected(source, scopeWith(source, List.of(), List.of(), Map.of()),
                    TerminalRejectionReason.SOLUTION_REFERENCE_SCOPE);
            ScopedSolution stored = seededScope(source).solutions().get(0);
            for (ScopedSolution changed : List.of(
                    new ScopedSolution(stored.publicSolutionId(), stored.solutionId(), NOTE_ID, stored.status(), null,
                            2, stored.claimCoverage()),
                    new ScopedSolution(stored.publicSolutionId(), stored.solutionId(), NOTE_ID, stored.status(), null,
                            1, Map.of("claim-1", SummaryCoverage.SUPPORTING)),
                    new ScopedSolution(stored.publicSolutionId(), stored.solutionId(), 999L, stored.status(), null,
                            1, stored.claimCoverage()))) {
                assertRejected(source, scopeWith(source, List.of(), List.of(changed), Map.of()),
                        TerminalRejectionReason.SOLUTION_REFERENCE_SCOPE);
            }
        }
    }

    @Nested
    @DisplayName("envelope·실패·취소")
    class EnvelopeAndFailure {

        @Test
        @DisplayName("현재 run·attempt·fence가 아닌 terminal은 거부된다")
        void staleFenceRejected() {
            JsonNode source = pair(SEEDED).get("source");
            TerminalAcceptanceScope scope = seededScope(source);
            for (TerminalAcceptanceScope stale : List.of(
                    new TerminalAcceptanceScope(scope.runId(), 2, scope.fenceToken(), USER_ID, NOTE_ID,
                            scope.assets(), List.of(), Map.of()),
                    new TerminalAcceptanceScope(scope.runId(), 1, "fence-other", USER_ID, NOTE_ID,
                            scope.assets(), List.of(), Map.of()),
                    new TerminalAcceptanceScope("run-other", 1, scope.fenceToken(), USER_ID, NOTE_ID,
                            scope.assets(), List.of(), Map.of()))) {
                assertRejected(source, stale, TerminalRejectionReason.RUN_MISMATCH);
            }
        }

        @Test
        @DisplayName("고정 문구가 아닌 실패 message(내부 원문)는 거부되고 run.canceled는 이 경로가 받지 않는다")
        void failureAndCancel() {
            ObjectNode failure = copy(pair("failure").get("source"));
            ((ObjectNode) failure.get("payload")).put("message", "Traceback: sandbox stdout ...");
            assertRejected(failure, seededScope(failure), TerminalRejectionReason.FAILURE_PAYLOAD);

            ObjectNode canceled = copy(pair("failure").get("source"));
            canceled.put("type", "run.canceled");
            canceled.set("payload", TerminalFixtures.RAW.createObjectNode().put("cause", "user_requested"));
            assertRejected(canceled, seededScope(failure), TerminalRejectionReason.NOT_PUBLICATION_TERMINAL);
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private static AcceptedCompletion completion(JsonNode source, TerminalAcceptanceScope scope) {
        return (AcceptedCompletion) TerminalAcceptance.accept(stored(source), scope);
    }

    private static void assertRejected(JsonNode source, TerminalAcceptanceScope scope, TerminalRejectionReason reason) {
        assertThatThrownBy(() -> TerminalAcceptance.accept(stored(source), scope))
                .isInstanceOf(TerminalRejection.class)
                .extracting(error -> ((TerminalRejection) error).reason())
                .isEqualTo(reason);
    }

    private static TerminalAcceptanceScope scopeWith(JsonNode source, List<ScopedAsset> assets,
            List<ScopedSolution> solutions, Map<UUID, UUID> links) {
        return new TerminalAcceptanceScope(source.get("run_id").asText(), source.get("attempt").asInt(),
                source.get("fence_token").asText(), USER_ID, NOTE_ID, assets, solutions, links);
    }

    private static ScopedAsset withAsset(ScopedAsset base, long owner, long note, AssetStatus status,
            int currentVersion, Integer pageCount) {
        return new ScopedAsset(base.brainAssetId(), base.noteAssetId(), owner, note, status, 1, currentVersion,
                base.brainContentVersion(), base.sha256(), pageCount == null ? base.pageCount() : pageCount,
                base.pageUnderstandingVersion());
    }

    /** solution-1을 solution-8로 복제한 두 번째 공개 block·artifact·summary를 붙인다. */
    private static ObjectNode withSecondSolution(ObjectNode source) {
        ObjectNode payload = (ObjectNode) source.get("payload");
        ArrayNode blocks = (ArrayNode) payload.at("/assistant_response/response_pages/0/result_blocks");
        ObjectNode block = blocks.get(0).deepCopy();
        block.put("block_id", "block-8").put("solution_id", "solution-8");
        blocks.add(block);
        ObjectNode artifact = payload.at("/verified_solutions/0").deepCopy();
        artifact.put("solution_id", "solution-8");
        ((ObjectNode) artifact.get("verification")).put("solution_id", "solution-8");
        ((ArrayNode) payload.get("verified_solutions")).add(artifact);
        ObjectNode summary = payload.at("/verification_summary/0").deepCopy();
        summary.put("solution_id", "solution-8");
        ((ArrayNode) payload.get("verification_summary")).add(summary);
        return source;
    }
}
