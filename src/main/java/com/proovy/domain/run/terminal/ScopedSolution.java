package com.proovy.domain.run.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.proovy.domain.run.brain.SummaryCoverage;
import com.proovy.domain.solution.entity.VerifiedSolution;
import com.proovy.domain.solution.entity.VerifiedSolutionStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * run 요청이 Brain에 보낸 기존 Verified Solution 참조 하나(verified_solution_refs)와 그 row의 현재 상태.
 * terminal의 {@code existing_solution_references}·{@code revises_solution_id}는 이 집합 안만 가리킬 수 있다.
 *
 * @param publicSolutionId Brain에 보낸 neutral solution ID(요청 안에서 unique)
 * @param claimCoverage    저장된 claim별 coverage
 */
public record ScopedSolution(
        String publicSolutionId,
        UUID solutionId,
        long noteId,
        VerifiedSolutionStatus status,
        UUID revisesSolutionId,
        int solutionVersion,
        Map<String, SummaryCoverage> claimCoverage) {

    public ScopedSolution {
        Objects.requireNonNull(publicSolutionId, "publicSolutionId");
        Objects.requireNonNull(solutionId, "solutionId");
        Objects.requireNonNull(status, "status");
        claimCoverage = Map.copyOf(claimCoverage);
    }

    /** 저장된 row에서 만든다. publicSolutionId는 이 run 요청에서 그 row에 붙인 ID다. */
    public static ScopedSolution of(VerifiedSolution solution, String publicSolutionId) {
        Map<String, SummaryCoverage> coverage = new LinkedHashMap<>();
        for (JsonNode claim : solution.getClaims()) {
            String wire = claim.path("coverage").asText();
            coverage.put(claim.path("claim_id").asText(), wire.equals(SummaryCoverage.DIRECT.wireValue())
                    ? SummaryCoverage.DIRECT : SummaryCoverage.SUPPORTING);
        }
        return new ScopedSolution(publicSolutionId, solution.getId(), solution.getNoteId(), solution.getStatus(),
                solution.getRevisesSolutionId(), solution.getSolutionVersion(), coverage);
    }
}
