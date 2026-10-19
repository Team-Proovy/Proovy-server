package com.proovy.domain.run.contract;

/** POST /api/ai-runs/{runId}/cancel result. body 없는 요청만 받는다(있으면 RUN4001). */
public record RunCancelResult(String runId, PublicRunStatus status, CancelOutcome outcome) {
}
