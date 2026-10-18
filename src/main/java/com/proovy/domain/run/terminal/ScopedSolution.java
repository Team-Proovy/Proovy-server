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
            String claimId = claim.path("claim_id").asText("");
            String wire = claim.path("coverage").asText("");
            SummaryCoverage value = wire.equals(SummaryCoverage.DIRECT.wireValue()) ? SummaryCoverage.DIRECT
                    : wire.equals(SummaryCoverage.SUPPORTING.wireValue()) ? SummaryCoverage.SUPPORTING : null;
            // V1 claims CHECK가 막는 값이다. 들어와 있으면 저장 데이터 손상이므로 추측하지 않고 드러낸다.
            if (claimId.isEmpty() || value == null) {
                throw new IllegalStateException("stored verified solution claims are corrupt: " + solution.getId());
            }
            coverage.put(claimId, value);
        }
        return new ScopedSolution(publicSolutionId, solution.getId(), solution.getNoteId(), solution.getStatus(),
                solution.getRevisesSolutionId(), solution.getSolutionVersion(), coverage);
    }
}
