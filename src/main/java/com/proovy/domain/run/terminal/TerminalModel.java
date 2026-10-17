package com.proovy.domain.run.terminal;

import static com.proovy.domain.run.terminal.StrictJson.OPAQUE_ID;
import static com.proovy.domain.run.terminal.StrictJson.neutral;

import com.fasterxml.jackson.databind.JsonNode;
import com.proovy.domain.run.brain.SummaryCoverage;
import com.proovy.domain.run.entity.ClarificationReason;
import com.proovy.domain.run.entity.ResponseDisposition;
import com.proovy.domain.run.entity.RunFailureClass;
import com.proovy.domain.run.entity.RunFailureCode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Brain terminal-v2 {@code run.completed}·{@code run.failed} payload의 typed 사본과 record 단위 구조 규칙
 * (Brain {@code contracts/response.py}·{@code terminal.py}의 model validator). record 간 정합은
 * {@link TerminalAcceptance}가 검사한다. {@code toMap()}은 Brain {@code model_dump(exclude_none=True)}와 같은 모양이다.
 */
final class TerminalModel {

    static final int MAX_RESPONSE_PAGES = 20;
    static final int MAX_BLOCKS_PER_PAGE = 50;
    static final int MAX_SOURCE_REFERENCES = 200;
    static final int MAX_STEP_MARKS = 200;
    static final int MAX_QUOTED_SPANS = 100;
    static final int MAX_CLAIM_REFERENCES = 50;
    static final int MAX_TITLE_LENGTH = 300;
    static final int MAX_REASON_LENGTH = 500;
    static final int MAX_METHOD_LABEL_LENGTH = 200;
    static final int MAX_LABEL_LENGTH = 200;
    static final int MAX_CONDITIONS = 200;
    static final int MAX_ARTIFACTS = 50;
    static final int MAX_PROVENANCE = 200;
    static final int MAX_MODEL_LENGTH = 200;
    static final int MAX_FAILURE_MESSAGE_LENGTH = 500;

    static final String RESPONSE_SCHEMA_VERSION = "assistant-response-v1";
    static final String ARTIFACT_SCHEMA_VERSION = "verified-solution-v1";
    static final String VERIFICATION_VERSION = "chain-verification-report-v3";

    static final Pattern PAGE_ID = neutral("page");
    static final Pattern BLOCK_ID = neutral("block");
    static final Pattern SOURCE_REF_ID = neutral("sref");
    static final Pattern SOLUTION_ID = neutral("solution");
    static final Pattern STEP_ID = neutral("step");
    static final Pattern CLAIM_ID = neutral("claim");
    static final Pattern CONDITION_ID = Pattern.compile("condition-[1-9][0-9]*");
    static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    static final Pattern MODEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_./:-]*");
    private static final Pattern CONTROL_FIELD = Pattern.compile("[\\x00-\\x1f\\x7f-\\x9f]");

    private TerminalModel() {
    }

    enum Presentation { MAIN, THREAD_LIKE }

    enum BlockKind { SOLUTION, CHECK, EXPLANATION, RICH_TEXT, NOTICE }

    enum Badge { VERIFIED, PARTIALLY_VERIFIED, UNVERIFIED }

    enum MarkStatus { VERIFIED, FAILED, UNCONFIRMED }

    enum QuoteOrigin { USER_SUBMISSION, BRAIN_CANDIDATE }

    enum Tier { MAIN, STRONG }

    record Anchor(int start, int end) {
        static Anchor parse(JsonNode node, String path) {
            StrictJson json = StrictJson.object(node, path, Set.of("start", "end"));
            Anchor anchor = new Anchor(json.strictInt("start", 0), json.strictInt("end", 1));
            if (anchor.start >= anchor.end) {
                throw integrity(path, "anchor requires start < end");
            }
            return anchor;
        }

        Map<String, Object> toMap() {
            return map("start", start, "end", end);
        }
    }

    record Mark(String stepId, MarkStatus status, String reason, Anchor anchor) {
        static Mark parse(JsonNode node, String path) {
            StrictJson json = StrictJson.object(node, path, Set.of("step_id", "status", "reason", "anchor"));
            Mark mark = new Mark(json.id("step_id", STEP_ID), json.enumValue("status", MarkStatus.class),
                    noControl(json.optionalString("reason", 1, MAX_REASON_LENGTH), json.path("reason")),
                    json.present("anchor") ? Anchor.parse(json.raw("anchor"), json.path("anchor")) : null);
            if (mark.status == MarkStatus.UNCONFIRMED && mark.reason == null) {
                throw integrity(path, "unconfirmed mark requires a reason");
            }
            return mark;
        }

        Map<String, Object> toMap() {
            return map("step_id", stepId, "status", wire(status), "reason", reason,
                    "anchor", anchor == null ? null : anchor.toMap());
        }
    }

    record Quote(Anchor anchor, QuoteOrigin origin) {
        static Quote parse(JsonNode node, String path) {
            StrictJson json = StrictJson.object(node, path, Set.of("anchor", "origin"));
            return new Quote(Anchor.parse(json.required("anchor"), json.path("anchor")),
                    json.enumValue("origin", QuoteOrigin.class));
        }

        Map<String, Object> toMap() {
            return map("anchor", anchor.toMap(), "origin", wire(origin));
        }
    }

    record Block(BlockKind kind, String blockId, String body, Badge badge, String solutionId,
            List<String> claimIds, List<Mark> marks, String firstErrorStepId, List<Quote> quotes) {

        private static final Set<String> COMMON = Set.of("kind", "block_id", "body");

        static Block parse(JsonNode node, String path) {
            if (node == null || !node.isObject() || !node.hasNonNull("kind")) {
                throw StrictJson.malformed(path + ".kind", "is required");
            }
            BlockKind kind = StrictJson.object(node, path, Set.of("kind", "block_id", "body", "verification",
                    "solution_id", "claim_ids", "step_marks", "first_error_step_id", "quoted_spans"))
                    .enumValue("kind", BlockKind.class);
            Set<String> allowed = new HashSet<>(COMMON);
            switch (kind) {
                case SOLUTION -> allowed.addAll(Set.of("verification", "solution_id", "claim_ids", "step_marks"));
                case CHECK -> allowed.addAll(Set.of("step_marks", "first_error_step_id", "quoted_spans"));
                case EXPLANATION -> allowed.addAll(Set.of("solution_id", "claim_ids"));
                default -> {
                }
            }
            StrictJson json = StrictJson.object(node, path, allowed);
            String body = json.exactString("body", 1, SafeMarkdown.MAX_LENGTH);
            SafeMarkdown.validate(body, json.path("body"));
            List<Mark> marks = new ArrayList<>();
            List<JsonNode> markNodes = json.array("step_marks", MAX_STEP_MARKS);
            for (int i = 0; i < markNodes.size(); i++) {
                marks.add(Mark.parse(markNodes.get(i), json.path("step_marks") + "[" + i + "]"));
            }
            List<Quote> quotes = new ArrayList<>();
            List<JsonNode> quoteNodes = json.array("quoted_spans", MAX_QUOTED_SPANS);
            for (int i = 0; i < quoteNodes.size(); i++) {
                quotes.add(Quote.parse(quoteNodes.get(i), json.path("quoted_spans") + "[" + i + "]"));
            }
            List<String> claimIds = json.idList("claim_ids", CLAIM_ID, MAX_CLAIM_REFERENCES);
            unique(claimIds, Function.identity(), json.path("claim_ids"));
            Block block = new Block(kind, json.id("block_id", BLOCK_ID), body,
                    kind == BlockKind.SOLUTION ? json.enumValue("verification", Badge.class) : null,
                    json.optionalId("solution_id", SOLUTION_ID), claimIds, marks,
                    json.optionalId("first_error_step_id", STEP_ID), quotes);
            block.check(path);
            return block;
        }

        private void check(String path) {
            unique(marks, Mark::stepId, path + ".step_marks");
            List<Anchor> anchors = new ArrayList<>();
            marks.stream().map(Mark::anchor).filter(a -> a != null).forEach(anchors::add);
            quotes.forEach(quote -> anchors.add(quote.anchor()));
            checkAnchors(body, anchors, path);
            switch (kind) {
                case SOLUTION -> {
                    if (marks.stream().anyMatch(mark -> mark.status() == MarkStatus.FAILED)) {
                        throw verificationError(path, "published solution cannot carry a failed mark");
                    }
                    if (badge == Badge.UNVERIFIED) {
                        if (solutionId != null || !claimIds.isEmpty()) {
                            throw verificationError(path, "unverified solution cannot reference a Verified Solution");
                        }
                    } else {
                        if (solutionId == null) {
                            throw verificationError(path, "verified display requires a Verified Solution");
                        }
                        if (badge == Badge.VERIFIED
                                && marks.stream().anyMatch(mark -> mark.status() != MarkStatus.VERIFIED)) {
                            throw verificationError(path, "verified badge requires every mark verified");
                        }
                    }
                }
                case CHECK -> {
                    if (quotes.stream().anyMatch(quote -> quote.origin() != QuoteOrigin.USER_SUBMISSION)) {
                        throw integrity(path, "only user submissions may be quoted");
                    }
                    String firstFailed = marks.stream().filter(mark -> mark.status() == MarkStatus.FAILED)
                            .map(Mark::stepId).findFirst().orElse(null);
                    if (!java.util.Objects.equals(firstErrorStepId, firstFailed)) {
                        throw integrity(path, "first_error_step_id must name the first failed mark");
                    }
                }
                case EXPLANATION -> {
                    if (!claimIds.isEmpty() && solutionId == null) {
                        throw integrity(path, "claim references require their solution");
                    }
                }
                default -> {
                }
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = map("block_id", blockId, "body", body, "kind", wire(kind));
            switch (kind) {
                case SOLUTION -> {
                    out.put("verification", wire(badge));
                    putIfPresent(out, "solution_id", solutionId);
                    out.put("claim_ids", List.copyOf(claimIds));
                    out.put("step_marks", marks.stream().map(Mark::toMap).toList());
                }
                case CHECK -> {
                    out.put("step_marks", marks.stream().map(Mark::toMap).toList());
                    putIfPresent(out, "first_error_step_id", firstErrorStepId);
                    out.put("quoted_spans", quotes.stream().map(Quote::toMap).toList());
                }
                case EXPLANATION -> {
                    putIfPresent(out, "solution_id", solutionId);
                    out.put("claim_ids", List.copyOf(claimIds));
                }
                default -> {
                }
            }
            return out;
        }
    }

    record Page(String pageId, Presentation presentation, String title, List<Block> blocks) {
        static Page parse(JsonNode node, String path) {
            StrictJson json = StrictJson.object(node, path,
                    Set.of("response_page_id", "presentation", "title", "result_blocks"));
            String title = noControl(json.optionalString("title", 1, MAX_TITLE_LENGTH), json.path("title"));
            if (title != null && (title.contains("[[") || title.contains("]]"))) {
                throw integrity(json.path("title"), "title cannot contain source markers");
            }
            List<Block> blocks = new ArrayList<>();
            List<JsonNode> nodes = json.requiredArray("result_blocks", 1, MAX_BLOCKS_PER_PAGE);
            for (int i = 0; i < nodes.size(); i++) {
                blocks.add(Block.parse(nodes.get(i), json.path("result_blocks") + "[" + i + "]"));
            }
            unique(blocks, Block::blockId, json.path("result_blocks"));
            return new Page(json.id("response_page_id", PAGE_ID), json.enumValue("presentation", Presentation.class),
                    title, blocks);
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = map("response_page_id", pageId, "presentation", wire(presentation));
            putIfPresent(out, "title", title);
            out.put("result_blocks", blocks.stream().map(Block::toMap).toList());
            return out;
        }
    }

    record SourceRef(String sourceRefId, String assetId, int pageStart, int pageEnd) {
        static SourceRef parse(JsonNode node, String path) {
            StrictJson json = StrictJson.object(node, path, Set.of("source_ref_id", "asset_id", "page_start", "page_end"));
            SourceRef ref = new SourceRef(json.id("source_ref_id", SOURCE_REF_ID), json.id("asset_id", OPAQUE_ID),
                    json.strictInt("page_start", 1), json.strictInt("page_end", 1));
            if (ref.pageStart > ref.pageEnd) {
                throw integrity(path, "page range cannot be reversed");
            }
            return ref;
        }

        Map<String, Object> toMap() {
            return map("source_ref_id", sourceRefId, "asset_id", assetId, "page_start", pageStart, "page_end", pageEnd);
        }
    }

    record Response(List<Page> pages, List<SourceRef> refs) {
        static Response parse(JsonNode node, String path) {
            StrictJson json = StrictJson.object(node, path, Set.of("schema_version", "response_pages", "source_references"));
            json.literal("schema_version", RESPONSE_SCHEMA_VERSION);
            List<Page> pages = new ArrayList<>();
            List<JsonNode> pageNodes = json.requiredArray("response_pages", 1, MAX_RESPONSE_PAGES);
            for (int i = 0; i < pageNodes.size(); i++) {
                pages.add(Page.parse(pageNodes.get(i), json.path("response_pages") + "[" + i + "]"));
            }
            List<SourceRef> refs = new ArrayList<>();
            List<JsonNode> refNodes = json.array("source_references", MAX_SOURCE_REFERENCES);
            for (int i = 0; i < refNodes.size(); i++) {
                refs.add(SourceRef.parse(refNodes.get(i), json.path("source_references") + "[" + i + "]"));
            }
            Response response = new Response(pages, refs);
            response.check(path);
            return response;
        }

        private void check(String path) {
            unique(pages, Page::pageId, path + ".response_pages");
            unique(refs, SourceRef::sourceRefId, path + ".source_references");
            if (pages.stream().filter(page -> page.presentation() == Presentation.MAIN).count() != 1) {
                throw integrity(path + ".response_pages", "exactly one main page");
            }
            unique(blocks(), Block::blockId, path + ".response_pages");
            Map<String, List<int[]>> ranges = new HashMap<>();
            for (SourceRef ref : refs) {
                ranges.computeIfAbsent(ref.assetId(), key -> new ArrayList<>())
                        .add(new int[]{ref.pageStart(), ref.pageEnd()});
            }
            for (List<int[]> assetRanges : ranges.values()) {
                assetRanges.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
                for (int i = 1; i < assetRanges.size(); i++) {
                    if (assetRanges.get(i)[0] <= assetRanges.get(i - 1)[1]) {
                        throw integrity(path + ".source_references", "ranges of one asset must not overlap");
                    }
                }
            }
            Set<String> catalog = new HashSet<>();
            refs.forEach(ref -> catalog.add(ref.sourceRefId()));
            for (Block block : blocks()) {
                for (SafeMarkdown.Marker marker : SafeMarkdown.markers(block.body())) {
                    if (!catalog.contains(marker.refId())) {
                        throw integrity(path, "source marker references a missing catalog entry");
                    }
                }
            }
        }

        List<Block> blocks() {
            return pages.stream().flatMap(page -> page.blocks().stream()).toList();
        }
    }

    record UnconfirmedStep(String stepId, String reason) {
        Map<String, Object> toMap() {
            return map("step_id", stepId, "reason", reason);
        }
    }

    record ClaimSummary(String claimId, SummaryCoverage coverage, String methodLabel, List<UnconfirmedStep> unconfirmed) {
        Map<String, Object> toMap() {
            return map("claim_id", claimId, "coverage", coverage.wireValue(), "method_label", methodLabel,
                    "unconfirmed_steps", unconfirmed.stream().map(UnconfirmedStep::toMap).toList());
        }

        Map<String, String> unconfirmedByStep() {
            Map<String, String> out = new HashMap<>();
            unconfirmed.forEach(step -> out.put(step.stepId(), step.reason()));
            return out;
        }
    }

    record Summary(String solutionId, SummaryCoverage overall, List<ClaimSummary> claims) {
        static Summary parse(JsonNode node, String path) {
            StrictJson json = StrictJson.object(node, path, Set.of("solution_id", "overall_coverage", "claims"));
            List<ClaimSummary> claims = new ArrayList<>();
            List<JsonNode> nodes = json.requiredArray("claims", 1, MAX_CLAIM_REFERENCES);
            for (int i = 0; i < nodes.size(); i++) {
                String claimPath = json.path("claims") + "[" + i + "]";
                StrictJson claim = StrictJson.object(nodes.get(i), claimPath,
                        Set.of("claim_id", "coverage", "method_label", "unconfirmed_steps"));
                List<UnconfirmedStep> steps = new ArrayList<>();
                List<JsonNode> stepNodes = claim.array("unconfirmed_steps", MAX_STEP_MARKS);
                for (int j = 0; j < stepNodes.size(); j++) {
                    String stepPath = claim.path("unconfirmed_steps") + "[" + j + "]";
                    StrictJson step = StrictJson.object(stepNodes.get(j), stepPath, Set.of("step_id", "reason"));
                    steps.add(new UnconfirmedStep(step.id("step_id", STEP_ID),
                            noControl(step.string("reason", 1, MAX_REASON_LENGTH), step.path("reason"))));
                }
                unique(steps, UnconfirmedStep::stepId, claim.path("unconfirmed_steps"));
                ClaimSummary summary = new ClaimSummary(claim.id("claim_id", CLAIM_ID),
                        claim.enumValue("coverage", SummaryCoverage.class),
                        noControl(claim.string("method_label", 1, MAX_METHOD_LABEL_LENGTH), claim.path("method_label")),
                        steps);
                if (summary.coverage() == SummaryCoverage.DIRECT && !steps.isEmpty()) {
                    throw verificationError(claimPath, "direct coverage cannot list unconfirmed steps");
                }
                claims.add(summary);
            }
            unique(claims, ClaimSummary::claimId, json.path("claims"));
            Summary summary = new Summary(json.id("solution_id", SOLUTION_ID),
                    json.enumValue("overall_coverage", SummaryCoverage.class), claims);
            SummaryCoverage expected = claims.stream().anyMatch(c -> c.coverage() == SummaryCoverage.SUPPORTING)
                    ? SummaryCoverage.SUPPORTING : SummaryCoverage.DIRECT;
            if (summary.overall() != expected) {
                throw verificationError(path, "overall coverage must be supporting if any claim is");
            }
            return summary;
        }

        /** Brain {@code _check_summary_labels}: 허용 문구만, direct ⇔ 전 단계 실행 검사 문구. */
        void checkLabels(String path) {
            for (ClaimSummary claim : claims) {
                if (!PublicWording.METHOD_LABELS.contains(claim.methodLabel())
                        || claim.unconfirmed().stream().anyMatch(s -> !PublicWording.STEP_REASONS.contains(s.reason()))) {
                    throw verificationError(path, "summary requires public allowlisted wording");
                }
                if ((claim.coverage() == SummaryCoverage.DIRECT)
                        != PublicWording.FULL_STEP_VERIFICATION.equals(claim.methodLabel())) {
                    throw verificationError(path, "method wording must agree with coverage");
                }
            }
        }

        Map<String, Object> toMap() {
            return map("solution_id", solutionId, "overall_coverage", overall.wireValue(),
                    "claims", claims.stream().map(ClaimSummary::toMap).toList());
        }
    }

    record Condition(String conditionId, String text) {
        Map<String, Object> toMap() {
            return map("condition_id", conditionId, "text", text);
        }
    }

    record Claim(String claimId, String statement) {
    }

    record Artifact(String solutionId, String problemStatement, List<Condition> conditions, String solutionText,
            List<Claim> claims, List<Mark> marks, Summary verification, List<String> sourceRefIds,
            String revisesSolutionId) {

        static Artifact parse(JsonNode node, String path) {
            StrictJson json = StrictJson.object(node, path, Set.of("schema_version", "solution_id", "version",
                    "problem_statement", "problem_conditions", "solution_text", "claims", "step_marks",
                    "verification", "verification_version", "source_ref_ids", "revises_solution_id"));
            json.literal("schema_version", ARTIFACT_SCHEMA_VERSION);
            json.literal("version", 1);
            json.literal("verification_version", VERIFICATION_VERSION);
            List<Condition> conditions = new ArrayList<>();
            List<JsonNode> conditionNodes = json.requiredArray("problem_conditions", 1, MAX_CONDITIONS);
            for (int i = 0; i < conditionNodes.size(); i++) {
                String conditionPath = json.path("problem_conditions") + "[" + i + "]";
                StrictJson condition = StrictJson.object(conditionNodes.get(i), conditionPath,
                        Set.of("condition_id", "text"));
                conditions.add(new Condition(condition.id("condition_id", CONDITION_ID),
                        markdown(condition, "text")));
            }
            List<Claim> claims = new ArrayList<>();
            List<JsonNode> claimNodes = json.requiredArray("claims", 1, MAX_CLAIM_REFERENCES);
            for (int i = 0; i < claimNodes.size(); i++) {
                String claimPath = json.path("claims") + "[" + i + "]";
                StrictJson claim = StrictJson.object(claimNodes.get(i), claimPath, Set.of("claim_id", "statement"));
                claims.add(new Claim(claim.id("claim_id", CLAIM_ID), markdown(claim, "statement")));
            }
            List<Mark> marks = new ArrayList<>();
            List<JsonNode> markNodes = json.array("step_marks", MAX_STEP_MARKS);
            for (int i = 0; i < markNodes.size(); i++) {
                marks.add(Mark.parse(markNodes.get(i), json.path("step_marks") + "[" + i + "]"));
            }
            List<String> sourceRefIds = json.idList("source_ref_ids", SOURCE_REF_ID, MAX_SOURCE_REFERENCES);
            unique(sourceRefIds, Function.identity(), json.path("source_ref_ids"));
            Artifact artifact = new Artifact(json.id("solution_id", SOLUTION_ID), markdown(json, "problem_statement"),
                    conditions, markdown(json, "solution_text"), claims, marks,
                    Summary.parse(json.required("verification"), json.path("verification")), sourceRefIds,
                    json.optionalId("revises_solution_id", SOLUTION_ID));
            artifact.check(path);
            return artifact;
        }

        /** Brain {@code VerifiedSolution.validate_artifact}. */
        private void check(String path) {
            unique(conditions, Condition::conditionId, path + ".problem_conditions");
            unique(claims, Claim::claimId, path + ".claims");
            unique(marks, Mark::stepId, path + ".step_marks");
            if (solutionId.equals(revisesSolutionId)) {
                throw new TerminalRejection(TerminalRejectionReason.REVISION, path + ".revises_solution_id",
                        "a revision cannot replace itself");
            }
            Set<String> claimIds = new HashSet<>();
            claims.forEach(claim -> claimIds.add(claim.claimId()));
            Set<String> summaryClaimIds = new HashSet<>();
            verification.claims().forEach(claim -> summaryClaimIds.add(claim.claimId()));
            if (!verification.solutionId().equals(solutionId) || !claimIds.equals(summaryClaimIds)) {
                throw verificationError(path, "artifact claims must equal its verified claims");
            }
            if (marks.stream().anyMatch(mark -> mark.status() == MarkStatus.FAILED)) {
                throw verificationError(path, "a rejected solution is not a verified artifact");
            }
            boolean direct = verification.overall() == SummaryCoverage.DIRECT;
            if (direct && (marks.isEmpty() || marks.stream().anyMatch(mark -> mark.status() != MarkStatus.VERIFIED))) {
                throw verificationError(path, "direct coverage requires verified steps");
            }
            verification.checkLabels(path + ".verification");
            Map<String, String> unconfirmed = unconfirmedMarks(marks);
            if (!direct && marks.isEmpty() && verification.claims().stream()
                    .anyMatch(claim -> !PublicWording.INDEPENDENT_ANSWER_CHECK.equals(claim.methodLabel()))) {
                throw verificationError(path, "zero-step supporting verification requires answer fallback");
            }
            if (!direct && !marks.isEmpty() && (marks.stream().noneMatch(mark -> mark.status() == MarkStatus.VERIFIED)
                    || unconfirmed.values().stream().anyMatch(r -> !PublicWording.LEGITIMATE_LOGICAL_MOVE.equals(r)))) {
                throw verificationError(path, "supporting chain artifacts require settled evidence");
            }
            for (ClaimSummary claim : verification.claims()) {
                if (!claim.unconfirmedByStep().equals(unconfirmed)) {
                    throw verificationError(path, "unconfirmed artifact steps must match the summary");
                }
            }
            int length = Python.length(solutionText);
            if (marks.stream().anyMatch(mark -> mark.anchor() != null && mark.anchor().end() > length)) {
                throw verificationError(path, "artifact anchors must stay inside the solution");
            }
            Set<String> catalog = new HashSet<>(sourceRefIds);
            List<String> texts = new ArrayList<>(List.of(problemStatement, solutionText));
            conditions.forEach(condition -> texts.add(condition.text()));
            claims.forEach(claim -> texts.add(claim.statement()));
            for (String text : texts) {
                if (SafeMarkdown.markers(text).stream().anyMatch(marker -> !catalog.contains(marker.refId()))) {
                    throw new TerminalRejection(TerminalRejectionReason.SOURCE_CATALOG, path,
                            "artifact markers require its source references");
                }
            }
        }

        List<String> texts() {
            List<String> texts = new ArrayList<>(List.of(problemStatement, solutionText));
            conditions.forEach(condition -> texts.add(condition.text()));
            claims.forEach(claim -> texts.add(claim.statement()));
            return texts;
        }

        /** Brain verified-solution-v1 artifact dump(content hash 입력). */
        Map<String, Object> toMap() {
            Map<String, Object> out = map("schema_version", ARTIFACT_SCHEMA_VERSION, "solution_id", solutionId,
                    "version", 1, "problem_statement", problemStatement,
                    "problem_conditions", conditions.stream().map(Condition::toMap).toList(),
                    "solution_text", solutionText,
                    "claims", claims.stream().map(c -> map("claim_id", c.claimId(), "statement", c.statement())).toList(),
                    "step_marks", marks.stream().map(Mark::toMap).toList(),
                    "verification", verification.toMap(), "verification_version", VERIFICATION_VERSION,
                    "source_ref_ids", List.copyOf(sourceRefIds));
            putIfPresent(out, "revises_solution_id", revisesSolutionId);
            return out;
        }
    }

    record Existing(String solutionId, int version, Summary verification) {
        static Existing parse(JsonNode node, String path) {
            StrictJson json = StrictJson.object(node, path,
                    Set.of("solution_id", "version", "verification", "claim_match_checked"));
            Existing existing = new Existing(json.id("solution_id", SOLUTION_ID), json.strictInt("version", 1),
                    Summary.parse(json.required("verification"), json.path("verification")));
            if (!json.strictBool("claim_match_checked") || !existing.verification.solutionId().equals(existing.solutionId)) {
                throw verificationError(path, "existing reference requires checked claim agreement");
            }
            existing.verification.checkLabels(json.path("verification"));
            return existing;
        }
    }

    record Selected(String assetId, String contentVersion, String sha256, int page, String understandingVersion) {
        static Selected parse(JsonNode node, String path) {
            StrictJson json = StrictJson.object(node, path,
                    Set.of("asset_id", "content_version", "sha256", "page", "understanding_version"));
            return new Selected(json.id("asset_id", OPAQUE_ID), json.id("content_version", OPAQUE_ID),
                    json.id("sha256", SHA256), json.strictInt("page", 1), json.id("understanding_version", OPAQUE_ID));
        }
    }

    record Label(String sourceRefId, String label) {
        static Label parse(JsonNode node, String path) {
            StrictJson json = StrictJson.object(node, path, Set.of("source_ref_id", "label"));
            String label = noControl(json.string("label", 1, MAX_LABEL_LENGTH), json.path("label"));
            if (label.contains("[[") || label.contains("]]")) {
                throw StrictJson.malformed(json.path("label"), "label cannot contain source markers");
            }
            return new Label(json.id("source_ref_id", SOURCE_REF_ID), label);
        }
    }

    record Completed(ResponseDisposition disposition, ClarificationReason clarificationReason, Response response,
            List<Artifact> artifacts, List<Existing> existing, List<Selected> selected, String model, Tier tier,
            List<Summary> summaries, List<Label> labels) {

        static Completed parse(JsonNode node) {
            StrictJson json = StrictJson.object(node, "payload", Set.of("response_disposition", "clarification_reason",
                    "assistant_response", "verified_solutions", "existing_solution_references",
                    "selected_page_provenance", "model", "tier", "verification_summary", "source_labels"));
            List<Existing> existing = null;
            List<JsonNode> existingNodes = json.nullableArray("existing_solution_references", MAX_ARTIFACTS);
            if (existingNodes != null) {
                existing = new ArrayList<>();
                for (int i = 0; i < existingNodes.size(); i++) {
                    existing.add(Existing.parse(existingNodes.get(i),
                            json.path("existing_solution_references") + "[" + i + "]"));
                }
            }
            String model = json.string("model", 1, MAX_MODEL_LENGTH);
            if (!MODEL.matcher(model).matches()) {
                throw StrictJson.malformed(json.path("model"), "has an invalid format");
            }
            return new Completed(json.enumValue("response_disposition", ResponseDisposition.class),
                    json.optionalEnum("clarification_reason", ClarificationReason.class),
                    Response.parse(json.required("assistant_response"), json.path("assistant_response")),
                    items(json, "verified_solutions", MAX_ARTIFACTS, Artifact::parse),
                    existing,
                    items(json, "selected_page_provenance", MAX_PROVENANCE, Selected::parse),
                    model, json.enumValue("tier", Tier.class),
                    items(json, "verification_summary", MAX_ARTIFACTS, Summary::parse),
                    items(json, "source_labels", MAX_SOURCE_REFERENCES, Label::parse));
        }

        List<Existing> existingOrEmpty() {
            return existing == null ? List.of() : existing;
        }
    }

    record Failed(RunFailureCode code, AttachmentFailureReason reason, String message, RunFailureClass failureClass,
            boolean recoverable) {

        static Failed parse(JsonNode node) {
            StrictJson json = StrictJson.object(node, "payload",
                    Set.of("code", "reason", "message", "failure_class", "recoverable"));
            return new Failed(json.enumValue("code", RunFailureCode.class),
                    json.optionalEnum("reason", AttachmentFailureReason.class),
                    json.string("message", 1, MAX_FAILURE_MESSAGE_LENGTH),
                    json.enumValue("failure_class", RunFailureClass.class), json.strictBool("recoverable"));
        }
    }

    private interface ItemParser<T> {
        T parse(JsonNode node, String path);
    }

    private static <T> List<T> items(StrictJson json, String key, int max, ItemParser<T> parser) {
        List<T> out = new ArrayList<>();
        List<JsonNode> nodes = json.array(key, max);
        for (int i = 0; i < nodes.size(); i++) {
            out.add(parser.parse(nodes.get(i), json.path(key) + "[" + i + "]"));
        }
        return out;
    }

    private static String markdown(StrictJson json, String key) {
        String text = json.string(key, 1, SafeMarkdown.MAX_LENGTH);
        SafeMarkdown.validate(text, json.path(key));
        return text;
    }

    static Map<String, String> unconfirmedMarks(List<Mark> marks) {
        Map<String, String> out = new HashMap<>();
        marks.stream().filter(mark -> mark.status() == MarkStatus.UNCONFIRMED)
                .forEach(mark -> out.put(mark.stepId(), mark.reason()));
        return out;
    }

    /** Brain {@code _check_body_anchors}: anchor는 body(code point) 안이고 source marker를 가르지 않는다. */
    private static void checkAnchors(String body, List<Anchor> anchors, String path) {
        int length = Python.length(body);
        List<SafeMarkdown.Marker> markers = SafeMarkdown.markers(body);
        for (Anchor anchor : anchors) {
            if (anchor.end() > length) {
                throw integrity(path, "anchor must stay inside the body");
            }
            for (SafeMarkdown.Marker marker : markers) {
                if ((marker.start() < anchor.start() && anchor.start() < marker.end())
                        || (marker.start() < anchor.end() && anchor.end() < marker.end())) {
                    throw integrity(path, "anchor must not split a source marker");
                }
            }
        }
    }

    private static String noControl(String value, String path) {
        if (value != null && CONTROL_FIELD.matcher(value).find()) {
            throw StrictJson.malformed(path, "cannot contain control characters");
        }
        return value;
    }

    static <T> void unique(Collection<T> values, Function<T, ?> identity, String path) {
        Set<Object> seen = new HashSet<>();
        for (T value : values) {
            if (!seen.add(identity.apply(value))) {
                throw integrity(path, "duplicate identifier");
            }
        }
    }

    static String wire(Enum<?> value) {
        return StrictJson.wireValue(value);
    }

    /** null 값 member는 넣지 않는다(exclude_none). */
    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            putIfPresent(out, (String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

    private static void putIfPresent(Map<String, Object> out, String key, Object value) {
        if (value != null) {
            out.put(key, value);
        }
    }

    static TerminalRejection integrity(String path, String problem) {
        return new TerminalRejection(TerminalRejectionReason.RESPONSE_INTEGRITY, path, problem);
    }

    static TerminalRejection verificationError(String path, String problem) {
        return new TerminalRejection(TerminalRejectionReason.VERIFICATION_INTEGRITY, path, problem);
    }
}
