package com.proovy.domain.run.admission;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RunAdmissionTransactionTest {

    @Test
    void autoTitleIsStrippedCollapsedQuestionPrefixInCodePoints() {
        assertThat(RunAdmissionTransaction.autoTitle("  x^2-5x+6=0\n\n풀어줘  ")).isEqualTo("x^2-5x+6=0 풀어줘");
        String astral = "𝑥".repeat(40);
        assertThat(RunAdmissionTransaction.autoTitle(astral))
                .isEqualTo("𝑥".repeat(RunAdmissionTransaction.AUTO_TITLE_MAX_CODE_POINTS));
        assertThat(RunAdmissionTransaction.autoTitle("가".repeat(29) + " 나다")).isEqualTo("가".repeat(29));
    }
}
