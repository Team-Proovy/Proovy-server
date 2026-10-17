package com.proovy.domain.run.brain;

import static com.proovy.domain.run.brain.InternalRunContract.atLeast;
import static com.proovy.domain.run.brain.InternalRunContract.violation;

/**
 * 같은 run의 이전 attempt가 누적한 사용량. fingerprint에서 제외되고 재시도·조회로 줄어들지 않는다.
 * 첫 전달은 {@link #ZERO}다.
 */
public record ExecutionUsage(
        long usedLlmCalls,
        long usedToolCalls,
        long usedOutputTokens,
        long usedCostMicros,
        long usedPageUnderstandings) {

    public static final ExecutionUsage ZERO = new ExecutionUsage(0, 0, 0, 0, 0);

    public ExecutionUsage {
        atLeast(usedLlmCalls, 0, "usage.used_llm_calls");
        atLeast(usedToolCalls, 0, "usage.used_tool_calls");
        atLeast(usedOutputTokens, 0, "usage.used_output_tokens");
        atLeast(usedCostMicros, 0, "usage.used_cost_micros");
        atLeast(usedPageUnderstandings, 0, "usage.used_page_understandings");
    }

    /** 모든 counter가 {@code previous} 이상인지. 재전달이 누적 예산을 되돌리지 못하게 한다. */
    public void requireNotBelow(ExecutionUsage previous) {
        if (usedLlmCalls < previous.usedLlmCalls || usedToolCalls < previous.usedToolCalls
                || usedOutputTokens < previous.usedOutputTokens || usedCostMicros < previous.usedCostMicros
                || usedPageUnderstandings < previous.usedPageUnderstandings) {
            throw violation("execution_options.usage", "must not decrease across deliveries");
        }
    }
}
