package com.proovy.domain.run.brain;

import static com.proovy.domain.run.brain.InternalRunContract.require;
import static com.proovy.domain.run.brain.InternalRunContract.versionLabel;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.OffsetDateTime;

/**
 * versioned 실행 예산 snapshot과 누적 counter.
 * {@code requestDeadlineAt}은 요청 접수 때 고정한 절대 시각(offset 필수)이고 fingerprint에서 제외된다.
 * 재전달은 {@link InternalRunRequest#forRetry}로만 만들며 deadline을 바꾸지 않는다.
 */
public record ExecutionOptions(
        String budgetPolicyVersion,
        @JsonFormat(shape = JsonFormat.Shape.STRING) OffsetDateTime requestDeadlineAt,
        ExecutionLimits limits,
        ExecutionUsage usage) {

    public ExecutionOptions {
        budgetPolicyVersion = versionLabel(budgetPolicyVersion, "execution_options.budget_policy_version");
        require(requestDeadlineAt, "execution_options.request_deadline_at");
        require(limits, "execution_options.limits");
        require(usage, "execution_options.usage");
    }
}
