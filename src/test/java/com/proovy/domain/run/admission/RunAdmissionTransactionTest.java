package com.proovy.domain.run.admission;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.PersistenceException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class RunAdmissionTransactionTest {

    @Test
    void autoTitleIsStrippedCollapsedQuestionPrefixInCodePoints() {
        assertThat(RunAdmissionTransaction.autoTitle("  x^2-5x+6=0\n\n풀어줘  ")).isEqualTo("x^2-5x+6=0 풀어줘");
        String astral = "𝑥".repeat(40);
        assertThat(RunAdmissionTransaction.autoTitle(astral))
                .isEqualTo("𝑥".repeat(RunAdmissionTransaction.AUTO_TITLE_MAX_CODE_POINTS));
        assertThat(RunAdmissionTransaction.autoTitle("가".repeat(29) + " 나다")).isEqualTo("가".repeat(29));
        // 중간 공백도 strip 집합(전각·줄 구분자)으로 접는다
        assertThat(RunAdmissionTransaction.autoTitle("미분\u3000\u3000방정식\u2028풀이")).isEqualTo("미분 방정식 풀이");
    }

    @Test
    void retriesOnlyUniqueRaceAndTransientSqlStates() {
        assertThat(RunAdmissionService.isRetryable(wrapped("23505"))).isTrue();
        assertThat(RunAdmissionService.isRetryable(wrapped("40001"))).isTrue();
        assertThat(RunAdmissionService.isRetryable(wrapped("40P01"))).isTrue();
        assertThat(RunAdmissionService.isRetryable(wrapped("55P03"))).isTrue();
        assertThat(RunAdmissionService.isRetryable(wrapped("08006"))).isTrue();
        // 번역되지 않은 JPA flush 예외도 원인 SQLState로 판정한다
        assertThat(RunAdmissionService.isRetryable(new PersistenceException(new SQLException("x", "08003")))).isTrue();
        assertThat(RunAdmissionService.isRetryable(wrapped("23514"))).isFalse(); // check_violation
        assertThat(RunAdmissionService.isRetryable(wrapped("22P05"))).isFalse(); // NUL
        assertThat(RunAdmissionService.isRetryable(wrapped("P0001"))).isFalse();
        assertThat(RunAdmissionService.isRetryable(new IllegalStateException("bug"))).isFalse();
    }

    private static RuntimeException wrapped(String sqlState) {
        return new DataIntegrityViolationException("db", new SQLException("db", sqlState));
    }
}
