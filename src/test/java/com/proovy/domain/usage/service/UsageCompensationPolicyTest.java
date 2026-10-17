package com.proovy.domain.usage.service;

import com.proovy.domain.run.entity.AiRunStatus;
import com.proovy.domain.run.entity.ResponseDisposition;
import com.proovy.domain.run.entity.RunCancelCause;
import com.proovy.domain.run.entity.RunFailureClass;
import com.proovy.domain.usage.entity.UsageCompensationReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static com.proovy.domain.usage.entity.UsageCompensationReason.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsageCompensationPolicyTest {

    /** docs/plan/05·07·12의 정책표. reason이 null이면 차감 유지(순사용량 1). */
    static Stream<Arguments> policyTable() {
        return Stream.of(
                Arguments.of(RunTerminalOutcome.completed(ResponseDisposition.SUBSTANTIVE), null),
                Arguments.of(RunTerminalOutcome.completed(ResponseDisposition.CLARIFICATION_ONLY), CLARIFICATION_ONLY),
                Arguments.of(RunTerminalOutcome.completed(ResponseDisposition.UNSUPPORTED_ONLY), UNSUPPORTED_ONLY),
                Arguments.of(RunTerminalOutcome.failed(RunFailureClass.VERIFICATION), VERIFICATION_FAILED),
                Arguments.of(RunTerminalOutcome.failed(RunFailureClass.BUDGET), BUDGET_EXCEEDED),
                Arguments.of(RunTerminalOutcome.failed(RunFailureClass.ATTACHMENT), INVALID_ATTACHMENT),
                Arguments.of(RunTerminalOutcome.failed(RunFailureClass.INFRASTRUCTURE), INFRASTRUCTURE_FAILURE),
                Arguments.of(RunTerminalOutcome.canceled(AiRunStatus.PENDING, RunCancelCause.USER_REQUESTED), PENDING_CANCELED),
                Arguments.of(RunTerminalOutcome.canceled(AiRunStatus.PENDING, RunCancelCause.ASSET_DELETING), PENDING_CANCELED),
                Arguments.of(RunTerminalOutcome.canceled(AiRunStatus.PENDING, RunCancelCause.BUDGET_EXHAUSTED), PENDING_CANCELED),
                Arguments.of(RunTerminalOutcome.canceled(AiRunStatus.RUNNING, RunCancelCause.USER_REQUESTED), null),
                Arguments.of(RunTerminalOutcome.canceled(AiRunStatus.RETRYING, RunCancelCause.USER_REQUESTED), null),
                Arguments.of(RunTerminalOutcome.canceled(AiRunStatus.RUNNING, RunCancelCause.ASSET_DELETING), null),
                Arguments.of(RunTerminalOutcome.canceled(AiRunStatus.RETRYING, RunCancelCause.ASSET_DELETING), null),
                Arguments.of(RunTerminalOutcome.canceled(AiRunStatus.RUNNING, RunCancelCause.BUDGET_EXHAUSTED), BUDGET_EXCEEDED),
                Arguments.of(RunTerminalOutcome.canceled(AiRunStatus.RETRYING, RunCancelCause.BUDGET_EXHAUSTED), BUDGET_EXCEEDED)
        );
    }

    @ParameterizedTest
    @MethodSource("policyTable")
    void decidesCompensationPerPolicyTable(RunTerminalOutcome outcome, UsageCompensationReason expected) {
        assertThat(UsageCompensationPolicy.decide(outcome)).isEqualTo(java.util.Optional.ofNullable(expected));
    }

    @Test
    void everyCompensationReasonIsReachable() {
        assertThat(policyTable().map(a -> a.get()[1]).filter(java.util.Objects::nonNull).distinct())
                .containsExactlyInAnyOrder((Object[]) UsageCompensationReason.values());
    }

    @Test
    void rejectsNonTerminalOrInconsistentOutcomes() {
        assertThatThrownBy(() -> new RunTerminalOutcome(AiRunStatus.RUNNING, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunTerminalOutcome(AiRunStatus.CANCELING, null, null,
                AiRunStatus.RUNNING, RunCancelCause.USER_REQUESTED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RunTerminalOutcome.completed(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RunTerminalOutcome.failed(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RunTerminalOutcome.canceled(AiRunStatus.CANCELING, RunCancelCause.USER_REQUESTED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunTerminalOutcome(AiRunStatus.FAILED, ResponseDisposition.SUBSTANTIVE,
                RunFailureClass.INFRASTRUCTURE, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
