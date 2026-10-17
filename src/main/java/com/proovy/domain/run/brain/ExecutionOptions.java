package com.proovy.domain.run.brain;

import static com.proovy.domain.run.brain.InternalRunContract.require;
import static com.proovy.domain.run.brain.InternalRunContract.versionLabel;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

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
        // Brain(pydantic)은 µs까지만 보존한다. 1.75 wireTime 관례와 같이 µs로 자른다.
        requestDeadlineAt = require(requestDeadlineAt, "execution_options.request_deadline_at")
                .truncatedTo(ChronoUnit.MICROS);
        require(limits, "execution_options.limits");
        require(usage, "execution_options.usage");
    }
}
