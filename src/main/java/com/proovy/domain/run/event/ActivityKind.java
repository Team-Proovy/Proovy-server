package com.proovy.domain.run.event;

import com.proovy.domain.run.entity.WireValue;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 1 Activity Kind 6종과 kind별 label allowlist (activity-v1, Brain {@code contracts/activity.py}).
 * <p>
 * label은 Activity의 유일한 자유 표시면이라 kind마다 닫혀 있다. graph node 이름·추론 조각·판정처럼 보이는 문구는
 * 어떤 label에도 들어갈 수 없다.
 */
public enum ActivityKind implements WireValue {
    INPUT_ANALYSIS("input_analysis", Set.of("analyzing_input", "reading_input")),
    SOLUTION_GENERATION("solution_generation", Set.of("drafting_solution", "revising_solution")),
    ASSET_UNDERSTANDING("asset_understanding", Set.of("reading_pages", "summarizing_pages")),
    NOTE_RETRIEVAL("note_retrieval", Set.of("searching_notes")),
    CODE_EXECUTION("code_execution", Set.of("running_calculation", "running_check_code")),
    VERIFICATION("verification", Set.of("verifying_steps"));

    private final String wireValue;
    private final Set<String> labels;

    ActivityKind(String wireValue, Set<String> labels) {
        this.wireValue = wireValue;
        this.labels = labels;
    }

    @Override
    public String wireValue() {
        return wireValue;
    }

    public boolean allowsLabel(String label) {
        return labels.contains(label);
    }

    /** code_execution·verification만 모든 phase에 method_label을 가진다(started 포함). */
    boolean hasMethodLabel() {
        return this == CODE_EXECUTION || this == VERIFICATION;
    }

    /** 정확히 일치하는 wire 값만 받는다(Brain {@code Literal} discriminator와 같다). */
    public static Optional<ActivityKind> of(String wireValue) {
        return Arrays.stream(values()).filter(kind -> kind.wireValue.equals(wireValue)).findFirst();
    }
}
