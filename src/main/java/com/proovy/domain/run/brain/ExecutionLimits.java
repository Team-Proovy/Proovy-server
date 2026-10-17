package com.proovy.domain.run.brain;

import static com.proovy.domain.run.brain.InternalRunContract.atLeast;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 요청 시점에 고정한 호출·token·비용·page·problem·claim 상한. fingerprint에 포함된다. */
public record ExecutionLimits(
        @JsonProperty(required = true) long maxLlmCalls,
        @JsonProperty(required = true) long maxToolCalls,
        @JsonProperty(required = true) long maxOutputTokens,
        @JsonProperty(required = true) long maxCostMicros,
        @JsonProperty(required = true) long maxPageUnderstandings,
        @JsonProperty(required = true) long maxProblems,
        @JsonProperty(required = true) long maxClaims) {

    public ExecutionLimits {
        atLeast(maxLlmCalls, 0, "limits.max_llm_calls");
        atLeast(maxToolCalls, 0, "limits.max_tool_calls");
        atLeast(maxOutputTokens, 0, "limits.max_output_tokens");
        atLeast(maxCostMicros, 0, "limits.max_cost_micros");
        atLeast(maxPageUnderstandings, 0, "limits.max_page_understandings");
        atLeast(maxProblems, 0, "limits.max_problems");
        atLeast(maxClaims, 0, "limits.max_claims");
    }
}
