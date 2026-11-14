package com.proovy.domain.run.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Phase 2 완료 확장(task 2.07, terminal-fixtures-v2.1·browser-run-fixtures-v1.1): {@code run.completed}의
 * {@code response_kind}·{@code assurance_scope}·{@code solution_checks}. 세 필드는 함께 오거나 모두 없다.
 * <p>
 * 원본 규칙은 proovy-brain {@code contracts/browser_solution_check.py}(pin commit)의 {@code CheckableStepView}·
 * {@code SolutionCheckView}·{@code CompletionExtension}이다. Server는 같은 규칙으로 다시 검사한다.
 * <ul>
 *   <li>재설명은 항상 {@code not_applicable}이고 checks가 없다.</li>
 *   <li>응답 배지는 {@code not_applicable}을 뺀 문제별 배지로 정해진다: 하나라도 unverified면 unverified, 아니고 verified가
 *       있으면 verified, 아니면 not_applicable(사용자 결정 2026-11-12).</li>
 *   <li>{@code hide_answer}면 모든 단계가 withheld(설명·수식 null), {@code hide_solution}이면 단계가 없다.</li>
 *   <li>단계 종류별 허용 결과·✓ 단계의 수식 필수·argue 단계 수식 금지·설명 SafeMarkdown·출처 마커 금지.</li>
 * </ul>
 * 저장은 source 철자(snake_case, null 값 key 생략) {@link #sourceChecks()}이고, browser 철자(camelCase, null key 채움)는
 * {@link #browserChecks(JsonNode)}가 만든다. 독립 답·{@code check_reason}·problem id는 source에도 없다(Brain이 내보내지 않는다).
 *
 * @param solutionChecks 문제별 공개 검산(Read-back 순서)
 */
public record CompletionExtension(ResponseKind responseKind, AssuranceScope assuranceScope,
                                  List<SolutionCheck> solutionChecks) {

    public static final int MAX_SOLUTION_CHECKS = 20;
    static final int MAX_STEPS = 80;
    static final int MAX_ATTEMPTS = 3;
    static final int TEXT_MAX = 10_000;
    static final int FORMULA_MAX = 2_000;
    static final int NAME_MAX = 64;
    static final int MAX_PROBLEM_NUMBER_LENGTH = 32;
    static final int MAX_CHOICE = 5;
    static final Set<String> FIELDS = Set.of("response_kind", "assurance_scope", "solution_checks");

    /** SafeMarkdown과 같은 제어 문자 집합(탭·줄바꿈·CR 제외). */
    private static final Pattern CONTROL = SafeMarkdown.CONTROL;
    private static final String SOURCE_MARKER_PREFIX = "[[source:";

    public CompletionExtension {
        solutionChecks = List.copyOf(solutionChecks);
    }

    public enum ResponseKind {
        STANDARD, RE_EXPLANATION;

        public String wireValue() {
            return StrictJson.wireValue(this);
        }
    }

    public enum AssuranceScope {
        VERIFIED, UNVERIFIED, NOT_APPLICABLE;

        public String wireValue() {
            return StrictJson.wireValue(this);
        }
    }

    enum Strategy { ANSWER_CROSS_CHECK, NONE }

    enum Disclosure { FULL, HIDE_ANSWER, HIDE_SOLUTION }

    enum StepKind { DEFINE, GIVEN, CLAIM, CASE, CHOOSE, ARGUE }

    enum CheckResult { VERIFIED, FALSIFIED, UNPARSEABLE, NOT_CHECKED }

    enum DescriptionFormat { SAFE_MARKDOWN, PLAIN_TEXT }

    /** Brain {@code ALLOWED_CHECK_RESULTS}. */
    private static final Map<StepKind, Set<CheckResult>> ALLOWED = Map.of(
            StepKind.DEFINE, EnumSet.of(CheckResult.NOT_CHECKED, CheckResult.UNPARSEABLE),
            StepKind.GIVEN, EnumSet.of(CheckResult.NOT_CHECKED, CheckResult.UNPARSEABLE),
            StepKind.CLAIM, EnumSet.of(CheckResult.VERIFIED, CheckResult.FALSIFIED, CheckResult.UNPARSEABLE),
            StepKind.CASE, EnumSet.allOf(CheckResult.class),
            StepKind.CHOOSE, EnumSet.allOf(CheckResult.class),
            StepKind.ARGUE, EnumSet.of(CheckResult.NOT_CHECKED));

    /** Brain {@code REQUIRED_FOR_VERIFIED}: ✓ 단계가 가져야 하는 수식 필드. */
    private static final Map<StepKind, List<String>> REQUIRED_FOR_VERIFIED = Map.of(
            StepKind.CLAIM, List.of("lhs", "rhs"),
            StepKind.CASE, List.of("covers"),
            StepKind.CHOOSE, List.of("value", "choice"));

    private static final List<String> STATEMENT_TEXT_FIELDS = List.of("name", "value", "cond", "covers", "lhs", "rhs");

    /** 응답 배지 규칙(Brain {@code overall_assurance_scope}). */
    public static AssuranceScope overall(List<AssuranceScope> scopes) {
        if (scopes.contains(AssuranceScope.UNVERIFIED)) {
            return AssuranceScope.UNVERIFIED;
        }
        if (scopes.contains(AssuranceScope.VERIFIED)) {
            return AssuranceScope.VERIFIED;
        }
        return AssuranceScope.NOT_APPLICABLE;
    }

    /**
     * payload에서 확장을 읽는다. 세 필드가 모두 없거나 null이면 null(확장 이전 terminal)이다.
     * 하나만 있거나 규칙을 어기면 {@link TerminalRejection}이다.
     */
    static CompletionExtension parse(JsonNode payload) {
        int present = 0;
        for (String field : FIELDS) {
            JsonNode value = payload.get(field);
            if (value != null && !value.isNull()) {
                present++;
            }
        }
        if (present == 0) {
            return null;
        }
        if (present != FIELDS.size()) {
            throw TerminalModel.verificationError("payload", "completion extension fields appear together");
        }
        // 세 필드만 떼어 닫힌 목록으로 읽는다(payload의 나머지 key는 TerminalModel.Completed가 검사한다).
        com.fasterxml.jackson.databind.node.ObjectNode fields = JsonNodeFactory.instance.objectNode();
        FIELDS.forEach(field -> fields.set(field, payload.get(field)));
        StrictJson json = StrictJson.object(fields, "payload", FIELDS);
        ResponseKind kind = json.enumValue("response_kind", ResponseKind.class);
        AssuranceScope scope = json.enumValue("assurance_scope", AssuranceScope.class);
        List<JsonNode> nodes = json.array("solution_checks", MAX_SOLUTION_CHECKS);
        List<SolutionCheck> checks = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            checks.add(SolutionCheck.parse(nodes.get(i), json.path("solution_checks") + "[" + i + "]"));
        }
        if (kind == ResponseKind.RE_EXPLANATION && (scope != AssuranceScope.NOT_APPLICABLE || !checks.isEmpty())) {
            throw TerminalModel.verificationError("payload", "a re-explanation is not_applicable without checks");
        }
        if (scope != overall(checks.stream().map(SolutionCheck::scope).toList())) {
            throw TerminalModel.verificationError("payload.assurance_scope",
                    "the response badge must follow the solution scopes");
        }
        return new CompletionExtension(kind, scope, checks);
    }


    /** 저장 철자(source snake_case, null 값 key 생략). */
    public JsonNode sourceChecks() {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        solutionChecks.forEach(check -> out.add(TerminalAcceptance.tree(check.toSource())));
        return out;
    }

    /**
     * 저장된 source 철자 checks를 browser 철자로 바꾼다(Brain {@code CompletionExtension.browser_payload}):
     * camelCase이고 선택 필드의 null key를 채우며, {@code sympyStatement} 안의 null 필드는 뺀다.
     */
    public static ArrayNode browserChecks(JsonNode stored) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        for (JsonNode check : stored) {
            ObjectNode browser = out.addObject();
            browser.set("problemNumber", nullable(check.get("problem_number")));
            browser.set("assuranceScope", check.get("assurance_scope"));
            browser.set("strategy", check.get("strategy"));
            browser.set("attempts", check.get("attempts"));
            JsonNode cross = check.get("cross_check");
            if (cross == null || cross.isNull()) {
                browser.putNull("crossCheck");
            } else {
                browser.putObject("crossCheck")
                        .put("independentAnswerAgrees", cross.get("independent_answer_agrees").booleanValue())
                        .put("stepCheckCompleted", cross.get("step_check_completed").booleanValue());
            }
            browser.set("disclosure", check.get("disclosure"));
            ArrayNode steps = browser.putArray("checkableSteps");
            for (JsonNode step : check.get("checkable_steps")) {
                ObjectNode item = steps.addObject();
                item.set("stepIndex", step.get("step_index"));
                item.set("stepKind", step.get("step_kind"));
                item.set("checkResult", step.get("check_result"));
                item.set("withheld", step.get("withheld"));
                item.set("description", nullable(step.get("description")));
                item.set("descriptionFormat", nullable(step.get("description_format")));
                item.set("sympyStatement", nullable(step.get("sympy_statement")));
            }
        }
        return out;
    }

    private static JsonNode nullable(JsonNode value) {
        return value == null ? JsonNodeFactory.instance.nullNode() : value;
    }

    /** 문제 하나의 공개 검산 요약과 단계. */
    public record SolutionCheck(String problemNumber, AssuranceScope scope, Strategy strategy, int attempts,
                                CrossCheck crossCheck, Disclosure disclosure, List<Step> steps) {

        public SolutionCheck {
            steps = List.copyOf(steps);
        }

        static SolutionCheck parse(JsonNode node, String path) {
            StrictJson json = StrictJson.object(node, path, Set.of("problem_number", "assurance_scope", "strategy",
                    "attempts", "cross_check", "disclosure", "checkable_steps"));
            String problemNumber = json.optionalString("problem_number", 1, MAX_PROBLEM_NUMBER_LENGTH);
            AssuranceScope scope = json.enumValue("assurance_scope", AssuranceScope.class);
            Strategy strategy = json.enumValue("strategy", Strategy.class);
            int attempts = json.strictInt("attempts", 1);
            if (attempts > MAX_ATTEMPTS) {
                throw StrictJson.malformed(json.path("attempts"), "is out of range");
            }
            CrossCheck cross = json.present("cross_check")
                    ? CrossCheck.parse(json.raw("cross_check"), json.path("cross_check")) : null;
            Disclosure disclosure = json.enumValue("disclosure", Disclosure.class);
            JsonNode rawSteps = json.required("checkable_steps");
            if (!rawSteps.isArray() || rawSteps.size() > MAX_STEPS) {
                throw StrictJson.malformed(json.path("checkable_steps"), "must be an array within its limit");
            }
            List<Step> steps = new ArrayList<>();
            for (int i = 0; i < rawSteps.size(); i++) {
                Step step = Step.parse(rawSteps.get(i), json.path("checkable_steps") + "[" + i + "]");
                // 2.06 SolutionVerification은 step_index가 0부터 빈틈없이 센다. 화면이 stepIndex를 키로 쓰므로 그대로 강제한다.
                if (step.index() != i) {
                    throw TerminalModel.verificationError(json.path("checkable_steps"),
                            "step_index must count from 0 without gaps");
                }
                steps.add(step);
            }
            SolutionCheck check = new SolutionCheck(problemNumber, scope, strategy, attempts, cross, disclosure, steps);
            check.validate(path);
            return check;
        }

        /** Brain {@code SolutionCheckView._check_view}. */
        private void validate(String path) {
            if (disclosure == Disclosure.HIDE_SOLUTION) {
                if (!steps.isEmpty()) {
                    throw TerminalModel.verificationError(path, "hide_solution sends no steps");
                }
            } else if (steps.isEmpty()) {
                throw TerminalModel.verificationError(path, "a disclosed solution sends its steps");
            }
            boolean withheld = disclosure == Disclosure.HIDE_ANSWER;
            if (steps.stream().anyMatch(step -> step.withheld() != withheld)) {
                throw TerminalModel.verificationError(path, "step withholding must follow the disclosure");
            }
            boolean falsified = steps.stream().anyMatch(step -> step.result() == CheckResult.FALSIFIED);
            if (strategy == Strategy.NONE) {
                if (scope != AssuranceScope.NOT_APPLICABLE || crossCheck != null) {
                    throw TerminalModel.verificationError(path, "an unchecked solution is not_applicable without flags");
                }
                return;
            }
            if (crossCheck == null) {
                throw TerminalModel.verificationError(path, "a cross-checked solution reports its flags");
            }
            if (scope == AssuranceScope.NOT_APPLICABLE) {
                throw TerminalModel.verificationError(path, "a cross-checked solution is verified or unverified");
            }
            boolean passed = crossCheck.independentAnswerAgrees() && crossCheck.stepCheckCompleted();
            if (scope == AssuranceScope.VERIFIED && (!passed || falsified)) {
                throw TerminalModel.verificationError(path, "verified needs agreement, a completed check and no failure");
            }
            if (scope == AssuranceScope.UNVERIFIED && passed && !falsified && disclosure != Disclosure.HIDE_SOLUTION) {
                throw TerminalModel.verificationError(path, "unverified needs disagreement, a failed check or a failure");
            }
        }

        Map<String, Object> toSource() {
            return TerminalModel.map("problem_number", problemNumber, "assurance_scope", scope.wireValue(),
                    "strategy", TerminalModel.wire(strategy), "attempts", attempts,
                    "cross_check", crossCheck == null ? null : crossCheck.toSource(),
                    "disclosure", TerminalModel.wire(disclosure),
                    "checkable_steps", steps.stream().map(Step::toSource).toList());
        }

    }

    /** 일치 여부 플래그만 — 독립 답 값·프로그램은 없다. */
    public record CrossCheck(boolean independentAnswerAgrees, boolean stepCheckCompleted) {

        static CrossCheck parse(JsonNode node, String path) {
            StrictJson json = StrictJson.object(node, path, Set.of("independent_answer_agrees", "step_check_completed"));
            return new CrossCheck(json.strictBool("independent_answer_agrees"), json.strictBool("step_check_completed"));
        }

        Map<String, Object> toSource() {
            return TerminalModel.map("independent_answer_agrees", independentAnswerAgrees,
                    "step_check_completed", stepCheckCompleted);
        }
    }

    /** 화면이 보는 Checkable Step 하나({@code check_reason} 없음). */
    public record Step(int index, StepKind kind, CheckResult result, boolean withheld, String description,
                       DescriptionFormat format, Map<String, Object> statement) {

        static Step parse(JsonNode node, String path) {
            StrictJson json = StrictJson.object(node, path, Set.of("step_index", "step_kind", "check_result",
                    "withheld", "description", "description_format", "sympy_statement"));
            int index = json.strictInt("step_index", 0);
            if (index >= MAX_STEPS) {
                throw StrictJson.malformed(json.path("step_index"), "is out of range");
            }
            StepKind kind = json.enumValue("step_kind", StepKind.class);
            CheckResult result = json.enumValue("check_result", CheckResult.class);
            boolean withheld = json.strictBool("withheld");
            String description = json.optionalString("description", 1, TEXT_MAX);
            DescriptionFormat format = json.optionalEnum("description_format", DescriptionFormat.class);
            Map<String, Object> statement = json.present("sympy_statement")
                    ? statement(json.raw("sympy_statement"), json.path("sympy_statement")) : null;
            Step step = new Step(index, kind, result, withheld, description, format, statement);
            step.validate(path);
            return step;
        }

        /** Brain {@code SympyStatement}: 알려진 필드만, 최소 하나, 문자열 길이·choice 범위. */
        private static Map<String, Object> statement(JsonNode node, String path) {
            StrictJson json = StrictJson.object(node, path, Set.of("name", "value", "cond", "covers", "lhs", "rhs",
                    "choice"));
            Object[] pairs = new Object[STATEMENT_TEXT_FIELDS.size() * 2 + 2];
            int i = 0;
            for (String field : STATEMENT_TEXT_FIELDS) {
                pairs[i++] = field;
                pairs[i++] = json.optionalString(field, 1, field.equals("name") ? NAME_MAX : FORMULA_MAX);
            }
            Integer choice = json.optionalStrictInt("choice", 1);
            if (choice != null && choice > MAX_CHOICE) {
                throw StrictJson.malformed(json.path("choice"), "is out of range");
            }
            pairs[i++] = "choice";
            pairs[i] = choice;
            Map<String, Object> out = TerminalModel.map(pairs);
            if (out.isEmpty()) {
                throw StrictJson.malformed(path, "needs at least one field");
            }
            return out;
        }

        /** Brain {@code CheckableStepView._check_view}. */
        private void validate(String path) {
            if (!ALLOWED.get(kind).contains(result)) {
                throw TerminalModel.verificationError(path, "check result is not allowed for this step kind");
            }
            if (withheld) {
                if (description != null || format != null || statement != null) {
                    throw TerminalModel.verificationError(path, "a withheld step carries no description or formula");
                }
                return;
            }
            if (description == null || format == null) {
                throw TerminalModel.verificationError(path, "a public step needs a description and its format");
            }
            if (kind == StepKind.ARGUE && statement != null) {
                throw TerminalModel.verificationError(path, "argue steps have no formula");
            }
            if (result == CheckResult.VERIFIED && (statement == null
                    || !statement.keySet().containsAll(REQUIRED_FOR_VERIFIED.getOrDefault(kind, List.of())))) {
                throw TerminalModel.verificationError(path, "a verified step needs the formula it was checked on");
            }
            if (CONTROL.matcher(description).find()) {
                throw StrictJson.malformed(path + ".description", "cannot contain control characters");
            }
            if (format == DescriptionFormat.SAFE_MARKDOWN) {
                if (description.contains(SOURCE_MARKER_PREFIX)) {
                    throw TerminalModel.verificationError(path + ".description", "step descriptions cannot cite sources");
                }
                SafeMarkdown.validate(description, path + ".description");
            }
        }

        Map<String, Object> toSource() {
            return TerminalModel.map("step_index", index, "step_kind", TerminalModel.wire(kind),
                    "check_result", TerminalModel.wire(result), "withheld", withheld, "description", description,
                    "description_format", format == null ? null : TerminalModel.wire(format),
                    "sympy_statement", statement);
        }
    }
}
