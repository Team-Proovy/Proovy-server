package com.proovy.domain.run.terminal;

import static com.proovy.domain.run.terminal.TerminalFixtures.copy;
import static com.proovy.domain.run.terminal.TerminalFixtures.event;
import static com.proovy.domain.run.terminal.TerminalFixtures.files;
import static com.proovy.domain.run.terminal.TerminalFixtures.load;
import static com.proovy.domain.run.terminal.TerminalFixtures.pair;
import static com.proovy.domain.run.terminal.TerminalFixtures.read;
import static com.proovy.domain.run.terminal.TerminalFixtures.seededScope;
import static com.proovy.domain.run.terminal.TerminalFixtures.stored;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proovy.domain.run.brain.SourceEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * terminal-v2 공용 fixture를 Java 수락 판정으로 다시 돌린다: 정상 pair는 수락되고 Brain·browser golden과 같은
 * canonical page·plain text·source snapshot·summary를 만들며, 거부 fixture는 모두 거부된다.
 * seeded scope는 fixture provenance와 같은 version으로 승인한 asset·solution이다(실제 S09 자산 경로 아님).
 */
class TerminalAcceptanceContractTest {

    static Stream<String> pairs() {
        return files("terminal-v2/pairs", ".json").keySet().stream();
    }

    static Stream<String> invalids() {
        return files("terminal-v2/invalid", ".json").keySet().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pairs")
    @DisplayName("정상 pair는 stored terminal·stream event 모두 수락되고 golden과 같은 저장 입력을 만든다")
    void pairIsAccepted(String name) {
        JsonNode fixture = pair(name);
        JsonNode source = fixture.get("source");
        if ("re_explanation.delta".equals(source.get("type").asText())) {
            SourceEvent parsed = event(source);
            assertThat(com.proovy.domain.run.event.SourceActivities.browserPayload(
                    parsed.type(), parsed.payload())).isEqualTo(fixture.get("browser").get("payload"));
            assertThatThrownBy(() -> TerminalAcceptance.accept(parsed, seededScope(source)))
                    .isInstanceOf(TerminalRejection.class);
            return;
        }
        AcceptedTerminal accepted = TerminalAcceptance.accept(stored(source), seededScope(source));
        assertThat(TerminalAcceptance.accept(event(source), seededScope(source))).isEqualTo(accepted);
        assertThat(accepted.runId()).isEqualTo(source.get("run_id").asText());
        assertThat(accepted.sourceEventId()).isEqualTo(source.get("source_event_id").asText());
        JsonNode payload = source.get("payload");
        if (accepted instanceof AcceptedFailure failure) {
            assertThat(failure.code().wireValue()).isEqualTo(payload.get("code").asText());
            assertThat(failure.failureClass().wireValue()).isEqualTo(payload.get("failure_class").asText());
            assertThat(failure.message()).isEqualTo(payload.get("message").asText());
            assertThat(failure.recoverable()).isEqualTo(payload.get("recoverable").asBoolean());
            return;
        }
        AcceptedCompletion completion = (AcceptedCompletion) accepted;
        JsonNode browser = fixture.get("browser").get("payload");
        assertThat(completion.disposition().wireValue()).isEqualTo(payload.get("response_disposition").asText());
        assertThat(completion.responsePages()).isEqualTo(payload.get("assistant_response").get("response_pages"));
        assertThat(completion.plainText()).isEqualTo(browser.get("assistantResponse").get("plainText").asText());
        assertThat(completion.verificationSummary()).isEqualTo(payload.get("verification_summary"));
        assertThat(completion.contentSha256()).matches("[0-9a-f]{64}");
        JsonNode labels = fixture.get("server").get("display_labels");
        JsonNode references = completion.sourceReferences();
        assertThat(references.size()).isEqualTo(payload.get("assistant_response").get("source_references").size());
        for (JsonNode reference : references) {
            assertThat(reference.get("display_label_snapshot").asText())
                    .isEqualTo(labels.get(reference.get("source_ref_id").asText()).asText());
            assertThat(reference.get("asset_id").isIntegralNumber()).isTrue();
        }
        assertThat(completion.verifiedSolutions()).hasSize(payload.path("verified_solutions").size());
        for (VerifiedSolutionInput solution : completion.verifiedSolutions()) {
            for (JsonNode claim : solution.claims()) {
                assertThat(claim.get("coverage").asText()).isIn("direct", "supporting");
            }
        }
        assertThat(completion.recheck().citedAssets()).hasSize(seededScope(source).assets().size());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalids")
    @DisplayName("거부 fixture는 모두 저장 입력 없이 거부된다(activity 위반은 terminal이 아니라서 거부)")
    void invalidIsRejected(String name) {
        JsonNode fixture = load("terminal-v2/invalid/" + name + ".json");
        assertThat(fixture.get("source_outcome").asText()).isEqualTo("reject");
        JsonNode source = fixture.get("source");
        SourceEvent parsed;
        try {
            parsed = event(source);
        } catch (IllegalArgumentException envelope) {
            // 1.76 envelope 검사(type union)가 먼저 거부한 경우. 그래도 저장 입력은 생기지 않는다.
            assertThat(name).isIn(ENVELOPE_REJECTED);
            return;
        }
        assertThat(name).isNotIn(ENVELOPE_REJECTED);
        if ("re_explanation.delta".equals(source.get("type").asText())) {
            assertThatThrownBy(() -> com.proovy.domain.run.event.SourceActivities.browserPayload(
                    parsed.type(), parsed.payload()))
                    .isInstanceOf(com.proovy.domain.run.event.ActivityContractViolation.class)
                    .satisfies(error -> assertThat(error.getMessage())
                            .doesNotContainPattern("[\\uAC00-\\uD7A3$]|https?://|Traceback"));
            return;
        }
        assertThatThrownBy(() -> TerminalAcceptance.accept(parsed, seededScope(source)))
                .isInstanceOf(TerminalRejection.class)
                // message에 payload 값(한국어 본문·math)이 들어가지 않는다.
                .satisfies(error -> assertThat(error.getMessage()).doesNotContainPattern("[\\uAC00-\\uD7A3$]"));
    }

    /** 1.76 SourceEvent envelope가 이미 거부하는 fixture(이 태스크 판정 전). */
    private static final List<String> ENVELOPE_REJECTED = List.of("raw-discriminator");

    @Test
    @DisplayName("정답 숨기기: 숨긴 구간이 title·plain text·page·source snapshot·summary 어디에도 남지 않는다")
    void hiddenAnswerDoesNotLeakIntoPublicStorage() {
        int checked = 0;
        for (String name : pairs().toList()) {
            JsonNode fixture = pair(name);
            JsonNode request = fixture.get("publication_request");
            if (request == null || !"hide_answer".equals(request.path("disclosure").path("scope").asText())) {
                continue;
            }
            List<String> hidden = withheld(request);
            assertThat(hidden).as(name).isNotEmpty();
            AcceptedCompletion completion = (AcceptedCompletion) TerminalAcceptance.accept(
                    stored(fixture.get("source")), seededScope(fixture.get("source")));
            String publicSurface = completion.plainText() + "\n" + completion.responsePages() + "\n"
                    + completion.sourceReferences() + "\n" + completion.verificationSummary() + "\n"
                    + (completion.extension() == null ? "" : completion.extension().sourceChecks());
            for (String fragment : hidden) {
                assertThat(publicSurface).as(name).doesNotContain(fragment);
                assertThat(publicSurface).as(name).doesNotContain(fragment.replace("$", ""));
            }
            checked++;
        }
        assertThat(checked).isEqualTo(4);
    }

    @Test
    @DisplayName("verified-answer-ref: answer_claim_id·answer_text는 저장 입력으로 추출되고 공개 표면에는 노출되지 않는다")
    void verifiedAnswerRefExtractsAnswerMetadataWithoutLeakingToBrowser() {
        JsonNode fixture = pair("verified-answer-ref");
        JsonNode source = fixture.get("source");
        AcceptedCompletion completion = (AcceptedCompletion) TerminalAcceptance.accept(stored(source),
                seededScope(source));

        assertThat(completion.verifiedSolutions()).hasSize(1);
        VerifiedSolutionInput solution = completion.verifiedSolutions().getFirst();
        assertThat(solution.answerClaimId()).isEqualTo("claim-1");
        assertThat(solution.answerText()).isEqualTo("$x=2$");

        String browserSurface = fixture.get("browser").toString() + "\n" + completion.plainText() + "\n"
                + completion.responsePages() + "\n" + completion.verificationSummary();
        assertThat(browserSurface).doesNotContain("answer_claim_id", "answer_text", "$x=2$", "x=2");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pairs")
    @DisplayName("2.07 확장: phase2 pair는 배지·응답 종류·검산을 저장 입력으로 넘기고 browser 철자가 golden과 같다")
    void completionExtensionMatchesGolden(String name) {
        JsonNode fixture = pair(name);
        JsonNode source = fixture.get("source");
        if (!source.get("type").asText().equals("run.completed")) {
            return;
        }
        JsonNode payload = source.get("payload");
        JsonNode browser = fixture.at("/browser/payload");
        AcceptedCompletion completion = (AcceptedCompletion) TerminalAcceptance.accept(stored(source),
                seededScope(source));
        if (!payload.has("response_kind")) {
            assertThat(completion.extension()).as(name).isNull();
            assertThat(browser.has("responseKind") || browser.has("solutionChecks")).isFalse();
            return;
        }
        CompletionExtension extension = completion.extension();
        assertThat(extension.responseKind().wireValue()).isEqualTo(payload.get("response_kind").asText());
        assertThat(extension.assuranceScope().wireValue()).isEqualTo(payload.get("assurance_scope").asText());
        // 저장 철자는 source 그대로, browser 철자는 null key를 채운 golden과 같다
        assertThat(extension.sourceChecks()).isEqualTo(payload.get("solution_checks"));
        assertThat(CompletionExtension.browserChecks(extension.sourceChecks())).isEqualTo(browser.get("solutionChecks"));
        assertThat(browser.get("responseKind")).isEqualTo(payload.get("response_kind"));
        assertThat(browser.get("assuranceScope")).isEqualTo(payload.get("assurance_scope"));

        // step_marks는 언제나 artifact chain mark 그대로다(공개 view·disclosure로 덮어쓰지 않는다, 리뷰 M1).
        for (int i = 0; i < completion.verifiedSolutions().size(); i++) {
            assertThat(completion.verifiedSolutions().get(i).stepMarks())
                    .isEqualTo(payload.at("/verified_solutions/" + i + "/step_marks"));
        }
        // R2-M1: 2.07 wire에는 검산과 solution_id를 잇는 키가 없어 verification_summary에 solution_check를 휴리스틱 복사하지 않는다.
        for (VerifiedSolutionInput solution : completion.verifiedSolutions()) {
            JsonNode summary = solution.verificationSummary();
            assertThat(summary.get("overall_coverage")).isNotNull();
            assertThat(summary.has("solution_check")).isFalse();
        }
    }

    @Test
    @DisplayName("2.07 확장 없는 terminal의 Verified Solution은 chain step mark를 그대로 저장한다")
    void legacyArtifactKeepsChainMarks() {
        JsonNode source = pair("direct-verified").get("source");
        AcceptedCompletion completion = (AcceptedCompletion) TerminalAcceptance.accept(stored(source),
                seededScope(source));
        assertThat(completion.extension()).isNull();
        assertThat(completion.verifiedSolutions().get(0).stepMarks())
                .isEqualTo(source.at("/payload/verified_solutions/0/step_marks"));
        assertThat(completion.verifiedSolutions().get(0).verificationSummary().has("solution_check")).isFalse();
    }

    @Test
    @DisplayName("browser-run-v1.1 stream: valid 확장은 source 철자로 통과·왕복하고, invalid 확장은 모두 거부된다")
    void browserStreamExtensions() {
        int valid = 0;
        for (var entry : files("browser-run-v1/streams/valid", ".json").entrySet()) {
            JsonNode completed = completedFrame(read(entry.getValue()));
            if (completed == null || !completed.has("responseKind")) {
                continue;
            }
            CompletionExtension extension = CompletionExtension.parse(sourceExtension(completed));
            assertThat(extension).as(entry.getKey()).isNotNull();
            assertThat(extension.assuranceScope().wireValue()).isEqualTo(completed.get("assuranceScope").asText());
            assertThat(CompletionExtension.browserChecks(extension.sourceChecks())).as(entry.getKey())
                    .isEqualTo(completed.get("solutionChecks"));
            valid++;
        }
        assertThat(valid).isEqualTo(8);
        int invalid = 0;
        for (var entry : files("browser-run-v1/streams/invalid", ".json").entrySet()) {
            JsonNode completed = completedFrame(read(entry.getValue()));
            if (completed == null || entry.getKey().startsWith("read-back-")
                    || entry.getKey().startsWith("re-explanation-delta-")
                    || EXTENSION_KEYS.stream().noneMatch(completed::has)) {
                continue;
            }
            JsonNode extension = sourceExtension(completed);
            assertThatThrownBy(() -> CompletionExtension.parse(extension)).as(entry.getKey())
                    .isInstanceOf(TerminalRejection.class)
                    .satisfies(error -> assertThat(error.getMessage()).doesNotContainPattern("[\\uAC00-\\uD7A3$]"));
            invalid++;
        }
        assertThat(invalid).isEqualTo(17);
    }

    @Test
    @DisplayName("2.07 확장: 안내 응답에 검산이 있으면 거부, 확장 필드는 함께 와야 한다")
    void guidanceCarriesNoChecks() {
        JsonNode verified = pair("phase2-verified").at("/source/payload");
        ObjectNode clarification = copy(pair("clarification").get("source"));
        ObjectNode payload = (ObjectNode) clarification.get("payload");
        payload.put("response_kind", "standard").put("assurance_scope", "verified")
                .set("solution_checks", verified.get("solution_checks"));
        assertThatThrownBy(() -> TerminalAcceptance.accept(stored(clarification), seededScope(clarification)))
                .isInstanceOf(TerminalRejection.class)
                .extracting(error -> ((TerminalRejection) error).reason())
                .isEqualTo(TerminalRejectionReason.DISPOSITION_STRUCTURE);
        // 안내 응답도 빈 검산의 not_applicable 확장은 받는다
        payload.put("assurance_scope", "not_applicable").putArray("solution_checks");
        AcceptedCompletion accepted = (AcceptedCompletion) TerminalAcceptance.accept(stored(clarification),
                seededScope(clarification));
        assertThat(accepted.extension().solutionChecks()).isEmpty();
        // 하나만 오면 거부
        payload.remove("assurance_scope");
        assertThatThrownBy(() -> TerminalAcceptance.accept(stored(clarification), seededScope(clarification)))
                .isInstanceOf(TerminalRejection.class);
    }

    @Test
    @DisplayName("근거 없는 verified 검산(풀이 수 초과)·새 풀이를 만드는 재설명·step_index 빈틈은 거부된다(리뷰 m1·m2)")
    void extensionMustAgreeWithArtifacts() {
        // verified 검산 2개 > 새 artifact 1개 + 기존 참조 0개
        ObjectNode twoVerified = copy(pair("phase2-verified").get("source"));
        ArrayNode checks = (ArrayNode) twoVerified.at("/payload/solution_checks");
        checks.add(((ObjectNode) checks.get(0).deepCopy()).put("problem_number", "16"));
        assertRejected(twoVerified, TerminalRejectionReason.VERIFICATION_INTEGRITY);

        // 재설명은 새 Verified Solution을 만들지 않는다
        ObjectNode reExplanation = copy(pair("phase2-verified").get("source"));
        ((ObjectNode) reExplanation.get("payload")).put("response_kind", "re_explanation")
                .put("assurance_scope", "not_applicable").putArray("solution_checks");
        assertRejected(reExplanation, TerminalRejectionReason.VERIFICATION_INTEGRITY);

        // step_index는 0부터 빈틈없이(2.06 규칙)
        ObjectNode gap = copy(pair("phase2-verified").get("source"));
        ((ObjectNode) gap.at("/payload/solution_checks/0/checkable_steps/1")).put("step_index", 5);
        assertRejected(gap, TerminalRejectionReason.VERIFICATION_INTEGRITY);
        ObjectNode duplicate = copy(pair("phase2-verified").get("source"));
        ((ObjectNode) duplicate.at("/payload/solution_checks/0/checkable_steps/1")).put("step_index", 0);
        assertRejected(duplicate, TerminalRejectionReason.VERIFICATION_INTEGRITY);
    }

    @Test
    @DisplayName("verified 1개·not_applicable 1개·artifact 1개(mixed-scope)는 1건만 수락되고 solution_check를 휴리스틱 부착하지 않는다")
    void mixedScopePairsTheVerifiedCheck() {
        JsonNode source = pair("phase2-mixed-scope").get("source");
        AcceptedCompletion completion = (AcceptedCompletion) TerminalAcceptance.accept(stored(source),
                seededScope(source));
        assertThat(completion.verifiedSolutions()).hasSize(1);
        assertThat(completion.verifiedSolutions().get(0).verificationSummary().has("solution_check")).isFalse();
        assertThat(completion.extension().sourceChecks()).hasSize(2);
    }

    @Test
    @DisplayName("R2-M1: solution_id 없는 wire에서 artifact 1개·verified 1개·unverified 1개여도 solution_check를 휴리스틱 복사하지 않는다")
    void unkeyedSolutionCheckIsNotGuessedOntoSolutionSummary() {
        ObjectNode source = copy(pair("phase2-verified").get("source"));
        ((ObjectNode) source.at("/payload/verified_solutions/0")).put("problem_statement", "문제 16 풀이");
        ObjectNode payload = (ObjectNode) source.get("payload");
        payload.put("assurance_scope", "unverified");
        ArrayNode checks = (ArrayNode) payload.get("solution_checks");
        checks.add(unverifiedCheckNode("16"));

        AcceptedCompletion completion = (AcceptedCompletion) TerminalAcceptance.accept(stored(source),
                seededScope(source));

        assertThat(completion.extension().assuranceScope()).isEqualTo(CompletionExtension.AssuranceScope.UNVERIFIED);
        assertThat(completion.extension().solutionChecks()).hasSize(2);
        assertThat(completion.verifiedSolutions()).hasSize(1);
        assertThat(completion.verifiedSolutions().get(0).verificationSummary().has("solution_check")).isFalse();
    }

    @Test
    @DisplayName("R2-M2: unverified·not_applicable·빈 solution_checks는 새 Verified Solution을 만들 수 없고 혼합 응답은 verified 수만큼만 허용한다")
    void unverifiedOrEmptyChecksNeverCreateVerifiedSolution() {
        // 1) 단일 unverified + artifact 1개 -> 거부
        ObjectNode singleUnverified = copy(pair("phase2-verified").get("source"));
        ObjectNode suPayload = (ObjectNode) singleUnverified.get("payload");
        suPayload.put("assurance_scope", "unverified");
        ArrayNode suChecks = suPayload.putArray("solution_checks");
        suChecks.add(unverifiedCheckNode("15"));
        assertRejectedWithIntegrity(singleUnverified, "requires a verified solution check");

        // 2) 단일 not_applicable + artifact 1개 -> 거부
        ObjectNode singleNotApplicable = copy(pair("phase2-verified").get("source"));
        ObjectNode naPayload = (ObjectNode) singleNotApplicable.get("payload");
        naPayload.put("assurance_scope", "not_applicable");
        ArrayNode naChecks = naPayload.putArray("solution_checks");
        naChecks.add(pair("phase2-mixed-scope").at("/source/payload/solution_checks/1").deepCopy());
        assertRejectedWithIntegrity(singleNotApplicable, "requires a verified solution check");

        // 3) 빈 solution_checks + artifact 1개 -> 거부
        ObjectNode emptyChecks = copy(pair("phase2-verified").get("source"));
        ObjectNode ecPayload = (ObjectNode) emptyChecks.get("payload");
        ecPayload.put("assurance_scope", "not_applicable").putArray("solution_checks");
        assertRejectedWithIntegrity(emptyChecks, "requires a verified solution check");

        // 4) 기존 참조가 있어도 unverified 응답이 새 artifact를 만들면 거부
        ObjectNode withExistingRef = copy(singleUnverified);
        ObjectNode existingRef = pair("explain-existing").at("/source/payload/existing_solution_references/0").deepCopy();
        existingRef.put("solution_id", "solution-99");
        ((ObjectNode) existingRef.get("verification")).put("solution_id", "solution-99");
        ((ObjectNode) withExistingRef.get("payload")).putArray("existing_solution_references").add(existingRef);
        ((ArrayNode) withExistingRef.at("/payload/verification_summary"))
                .add(existingRef.get("verification").deepCopy());
        ObjectNode existingBlock = pair("explain-existing")
                .at("/source/payload/assistant_response/response_pages/0/result_blocks/0").deepCopy();
        existingBlock.put("block_id", "block-99").put("solution_id", "solution-99");
        ((ArrayNode) withExistingRef.at("/payload/assistant_response/response_pages/0/result_blocks"))
                .add(existingBlock);
        assertRejectedWithIntegrity(withExistingRef, "requires a verified solution check");

        // 5) verified 1 + unverified 1인데 새 artifact가 2개 -> 거부 (artifacts=2 > verified=1)
        ObjectNode twoArtifactsOneVerified = copy(pair("phase2-verified").get("source"));
        ObjectNode taPayload = (ObjectNode) twoArtifactsOneVerified.get("payload");
        taPayload.put("assurance_scope", "unverified");
        ((ArrayNode) taPayload.get("solution_checks")).add(unverifiedCheckNode("16"));
        ObjectNode secondArtifact = ((ObjectNode) taPayload.get("verified_solutions").get(0)).deepCopy();
        secondArtifact.put("solution_id", "solution-16");
        ((ObjectNode) secondArtifact.get("verification")).put("solution_id", "solution-16");
        ((ArrayNode) taPayload.get("verified_solutions")).add(secondArtifact);
        ((ArrayNode) taPayload.get("verification_summary")).add(secondArtifact.get("verification").deepCopy());
        ObjectNode secondBlock = ((ObjectNode) taPayload.at("/assistant_response/response_pages/0/result_blocks/0"))
                .deepCopy();
        secondBlock.put("block_id", "block-99").put("solution_id", "solution-16");
        ((ArrayNode) taPayload.at("/assistant_response/response_pages/0/result_blocks")).add(secondBlock);
        assertRejectedWithIntegrity(twoArtifactsOneVerified, "requires a verified solution check");

        // 6) 혼합 응답(verified 1 + unverified 1 + artifact 1)은 배지 unverified로 1건 수락
        ObjectNode mixedVerifiedAndUnverified = copy(pair("phase2-verified").get("source"));
        ((ObjectNode) mixedVerifiedAndUnverified.get("payload")).put("assurance_scope", "unverified");
        ((ArrayNode) mixedVerifiedAndUnverified.at("/payload/solution_checks")).add(unverifiedCheckNode("16"));
        AcceptedCompletion mixedAccepted = (AcceptedCompletion) TerminalAcceptance.accept(
                stored(mixedVerifiedAndUnverified), seededScope(mixedVerifiedAndUnverified));
        assertThat(mixedAccepted.extension().assuranceScope()).isEqualTo(CompletionExtension.AssuranceScope.UNVERIFIED);
        assertThat(mixedAccepted.verifiedSolutions()).hasSize(1);
        assertThat(mixedAccepted.extension().solutionChecks()).hasSize(2);

        // 7) 재설명(verified_solutions 비움)은 0건으로 수락
        AcceptedCompletion reExplanation = (AcceptedCompletion) TerminalAcceptance.accept(
                stored(pair("phase2-re-explanation").get("source")),
                seededScope(pair("phase2-re-explanation").get("source")));
        assertThat(reExplanation.verifiedSolutions()).isEmpty();
    }

    @Test
    @DisplayName("R2-M3: solution_checks의 단계 설명(safe_markdown·plain_text)·문제 번호·SymPy 수식의 signed URL은 거부되고 일반 URL은 통과한다")
    void extensionTextsRejectSignedUrlsAcrossAllFields() {
        // 1) safe_markdown step description 내 X-Goog-Signature
        ObjectNode mdSigned = copy(pair("phase2-verified").get("source"));
        ((ObjectNode) mdSigned.at("/payload/solution_checks/0/checkable_steps/0"))
                .put("description", "풀이 근거 [링크](https://storage.example.com/p?X-Goog-Signature=synthetic-sig)");
        assertForbiddenWithoutLeak(mdSigned, "X-Goog-Signature");

        // 2) plain_text step description 내 X-Amz-Signature
        ObjectNode plainSigned = copy(pair("phase2-mixed-scope").get("source"));
        ((ObjectNode) plainSigned.at("/payload/solution_checks/1/checkable_steps/0"))
                .put("description_format", "plain_text")
                .put("description", "참고 https://bucket.example.com/p?X-Amz-Signature=synthetic-sig");
        assertForbiddenWithoutLeak(plainSigned, "X-Amz-Signature");

        // 3) problem_number 내 X-Goog-Signature (MAX_PROBLEM_NUMBER_LENGTH=32 이하)
        ObjectNode problemNumberSigned = copy(pair("phase2-verified").get("source"));
        ((ObjectNode) problemNumberSigned.at("/payload/solution_checks/0"))
                .put("problem_number", "https://x.co?X-Goog-Signature=s");
        assertForbiddenWithoutLeak(problemNumberSigned, "X-Goog-Signature");

        // 4) sympy_statement 문자열 필드(name·lhs) 내 signed URL / AWSAccessKeyId
        ObjectNode sympySigned = copy(pair("phase2-verified").get("source"));
        ObjectNode stmt = (ObjectNode) sympySigned.at("/payload/solution_checks/0/checkable_steps/0/sympy_statement");
        String firstStringKey = null;
        var fieldNames = stmt.fieldNames();
        while (fieldNames.hasNext()) {
            String key = fieldNames.next();
            if (!"kind".equals(key) && stmt.get(key).isTextual()) {
                firstStringKey = key;
                break;
            }
        }
        assertThat(firstStringKey).isNotNull();
        stmt.put(firstStringKey, "https://x.co?AWSAccessKeyId=AKIAEXAMPLE&Signature=s");
        assertForbiddenWithoutLeak(sympySigned, "AWSAccessKeyId");

        ObjectNode sympyLhsSigned = copy(pair("phase2-verified").get("source"));
        ArrayNode steps = ((ObjectNode) sympyLhsSigned.at("/payload/solution_checks/0")).putArray("checkable_steps");
        ObjectNode claimStep = steps.addObject()
                .put("step_index", 0)
                .put("step_kind", "claim")
                .put("check_result", "verified")
                .put("withheld", false)
                .put("description", "$x = 1$")
                .put("description_format", "safe_markdown");
        claimStep.putObject("sympy_statement")
                .put("lhs", "https://example.com/p?X-Amz-Signature=synthetic-sig")
                .put("rhs", "1");
        assertForbiddenWithoutLeak(sympyLhsSigned, "X-Amz-Signature");

        // 5) 오탐 방지: 서명 없는 일반 HTTPS 링크와 정상 수식은 통과한다
        ObjectNode normalLink = copy(pair("phase2-verified").get("source"));
        ((ObjectNode) normalLink.at("/payload/solution_checks/0/checkable_steps/0"))
                .put("description", "공식 해설 [참고](https://example.com/guide) 및 $x = 3$");
        assertThat(TerminalAcceptance.accept(stored(normalLink), seededScope(normalLink)))
                .isInstanceOf(AcceptedCompletion.class);
    }

    private ObjectNode unverifiedCheckNode(String problemNumber) {
        ObjectNode check = copy(pair("phase2-verified").at("/source/payload/solution_checks/0"));
        check.put("problem_number", problemNumber);
        check.put("assurance_scope", "unverified");
        ((ObjectNode) check.get("cross_check")).put("independent_answer_agrees", false);
        ArrayNode steps = check.putArray("checkable_steps");
        ObjectNode claimStep = steps.addObject()
                .put("step_index", 0)
                .put("step_kind", "claim")
                .put("check_result", "falsified")
                .put("withheld", false)
                .put("description", "$x = 1$")
                .put("description_format", "safe_markdown");
        claimStep.putObject("sympy_statement").put("lhs", "x").put("rhs", "1");
        return check;
    }

    private static void assertRejectedWithIntegrity(JsonNode source, String messageFragment) {
        assertThatThrownBy(() -> TerminalAcceptance.accept(stored(source), seededScope(source)))
                .isInstanceOf(TerminalRejection.class)
                .satisfies(error -> {
                    TerminalRejection rejection = (TerminalRejection) error;
                    assertThat(rejection.reason()).as(rejection.getMessage())
                            .isEqualTo(TerminalRejectionReason.VERIFICATION_INTEGRITY);
                    assertThat(rejection.getMessage()).contains(messageFragment);
                });
    }

    private static void assertForbiddenWithoutLeak(JsonNode source, String leakedMarker) {
        assertThatThrownBy(() -> TerminalAcceptance.accept(stored(source), seededScope(source)))
                .isInstanceOf(TerminalRejection.class)
                .satisfies(error -> {
                    TerminalRejection rejection = (TerminalRejection) error;
                    assertThat(rejection.reason()).isEqualTo(TerminalRejectionReason.FORBIDDEN_CONTENT);
                    assertThat(rejection.field()).isEqualTo("payload");
                    assertThat(rejection.getMessage()).doesNotContain(leakedMarker).doesNotContain("http");
                });
    }

    private static void assertRejected(JsonNode source, TerminalRejectionReason reason) {
        assertThatThrownBy(() -> TerminalAcceptance.accept(stored(source), seededScope(source)))
                .isInstanceOf(TerminalRejection.class)
                .extracting(error -> ((TerminalRejection) error).reason()).isEqualTo(reason);
    }

    private static final List<String> EXTENSION_KEYS = List.of("responseKind", "assuranceScope", "solutionChecks");

    private static JsonNode completedFrame(JsonNode stream) {
        for (JsonNode frame : stream.get("frames")) {
            if (frame.get("type").asText().equals("run.completed")) {
                return frame.get("payload");
            }
        }
        return null;
    }

    /** browser 확장 필드만 source 철자(snake_case, null 값 key 생략)로 되돌린다. */
    private static JsonNode sourceExtension(JsonNode completed) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        for (String key : EXTENSION_KEYS) {
            if (completed.has(key)) {
                out.set(snakeKey(key), snakeWithoutNulls(completed.get(key)));
            }
        }
        return out;
    }

    private static JsonNode snakeWithoutNulls(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            node.properties().forEach(entry -> {
                if (!entry.getValue().isNull()) {
                    out.set(snakeKey(entry.getKey()), snakeWithoutNulls(entry.getValue()));
                }
            });
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> out.add(snakeWithoutNulls(item)));
            return out;
        }
        return node;
    }

    private static String snakeKey(String key) {
        return key.replaceAll("([A-Z])", "_$1").toLowerCase();
    }

    @Test
    @DisplayName("같은 의미의 terminal 재전달은 같은 저장 입력(content hash 포함)을 만든다")
    void sameSemanticRedeliveryBuildsSameInput() {
        JsonNode redelivery = load("terminal-v2/redelivery.json").get("same_semantic");
        AcceptedTerminal first = TerminalAcceptance.accept(stored(redelivery.get("first")),
                seededScope(redelivery.get("first")));
        AcceptedTerminal retry = TerminalAcceptance.accept(stored(redelivery.get("retry")),
                seededScope(redelivery.get("retry")));
        assertThat(retry).isEqualTo(first);
    }

    @Test
    @DisplayName("response-v1: valid는 파싱되고 invalid는 거부된다; browser golden의 plainText를 같은 규칙으로 파생한다")
    void responseFixtures() {
        files("response-v1/valid", ".json").forEach((name, path) ->
                TerminalModel.Response.parse(read(path), "assistant_response"));
        files("response-v1/invalid", ".json").forEach((name, path) ->
                assertThatThrownBy(() -> TerminalModel.Response.parse(read(path), "assistant_response"))
                        .as(name).isInstanceOf(TerminalRejection.class));
        TerminalModel.Response response = TerminalModel.Response.parse(
                load("response-v1/valid/verified-solution.json"), "assistant_response");
        assertThat(TerminalAcceptance.plainText(response))
                .isEqualTo(load("response-v1/browser/verified-solution.browser.json").get("plainText").asText());
    }

    @Test
    @DisplayName("response-v1 SafeMarkdown: allowed는 통과, rejected는 UNSAFE_MARKDOWN")
    void safeMarkdownFixtures() {
        files("response-v1/markdown/allowed", ".md").forEach((name, path) ->
                SafeMarkdown.validate(TerminalFixtures.text("response-v1/markdown/allowed/" + name + ".md"), name));
        files("response-v1/markdown/rejected", ".md").forEach((name, path) ->
                assertThatThrownBy(() -> SafeMarkdown.validate(
                        TerminalFixtures.text("response-v1/markdown/rejected/" + name + ".md"), name))
                        .as(name).isInstanceOf(TerminalRejection.class)
                        .extracting(error -> ((TerminalRejection) error).reason())
                        .isEqualTo(TerminalRejectionReason.UNSAFE_MARKDOWN));
    }

    @Test
    @DisplayName("publication-v1 expected 산출물의 AssistantResponse·summary가 구조 검사를 통과한다")
    void publicationExpectedOutputsParse() {
        files("publication-v1/expected", ".json").forEach((name, path) -> {
            JsonNode expected = read(path);
            TerminalModel.Response.parse(expected.get("assistant_response"), "assistant_response");
            for (JsonNode summary : expected.get("verification_summary")) {
                TerminalModel.Summary.parse(summary, "verification_summary").checkLabels("verification_summary");
            }
        });
    }

    /** publication draft의 withheld span(code point offset) 원문. */
    private static List<String> withheld(JsonNode request) {
        List<String> fragments = new ArrayList<>();
        for (JsonNode page : request.get("pages")) {
            for (JsonNode block : page.get("blocks")) {
                String text = block.path("draft").path("text").asText();
                for (JsonNode span : block.path("draft").path("withheld")) {
                    int start = text.offsetByCodePoints(0, span.get("start").asInt());
                    int end = text.offsetByCodePoints(0, span.get("end").asInt());
                    fragments.add(text.substring(start, end).strip());
                }
            }
        }
        return fragments;
    }
}
