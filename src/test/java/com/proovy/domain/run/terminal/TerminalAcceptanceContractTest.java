package com.proovy.domain.run.terminal;

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
                    + completion.sourceReferences() + "\n" + completion.verificationSummary();
            for (String fragment : hidden) {
                assertThat(publicSurface).as(name).doesNotContain(fragment);
                assertThat(publicSurface).as(name).doesNotContain(fragment.replace("$", ""));
            }
            checked++;
        }
        assertThat(checked).isEqualTo(2);
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
