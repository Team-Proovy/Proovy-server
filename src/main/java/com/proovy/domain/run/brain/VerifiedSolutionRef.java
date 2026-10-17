package com.proovy.domain.run.brain;

import static com.proovy.domain.run.brain.InternalRunContract.MAX_IDENTIFIER_LENGTH;
import static com.proovy.domain.run.brain.InternalRunContract.MAX_PROBLEM_SUMMARY_CHARS;
import static com.proovy.domain.run.brain.InternalRunContract.MAX_REF_CLAIMS;
import static com.proovy.domain.run.brain.InternalRunContract.SOLUTION_ID;
import static com.proovy.domain.run.brain.InternalRunContract.list;
import static com.proovy.domain.run.brain.InternalRunContract.matching;
import static com.proovy.domain.run.brain.InternalRunContract.opaqueId;
import static com.proovy.domain.run.brain.InternalRunContract.require;
import static com.proovy.domain.run.brain.InternalRunContract.text;
import static com.proovy.domain.run.brain.InternalRunContract.unique;
import static com.proovy.domain.run.brain.InternalRunContract.violation;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.util.List;

/**
 * Server가 검증한 기존 Verified Solution 참조. fingerprint는 problem_summary·claims만 해시하고
 * status·revises_solution_id·source_message_id(수명주기 기록)는 제외한다.
 */
public record VerifiedSolutionRef(
        String solutionId,
        String sourceMessageId,
        SolutionReferenceStatus status,
        String revisesSolutionId,
        String problemSummary,
        @JsonSetter(nulls = Nulls.FAIL) List<VerifiedSolutionRefClaim> claims) {

    public VerifiedSolutionRef {
        solutionId = matching(solutionId, "verified_solution_refs.solution_id", SOLUTION_ID, MAX_IDENTIFIER_LENGTH);
        sourceMessageId = opaqueId(sourceMessageId, "verified_solution_refs.source_message_id");
        require(status, "verified_solution_refs.status");
        if (revisesSolutionId != null) {
            revisesSolutionId = matching(revisesSolutionId, "verified_solution_refs.revises_solution_id",
                    SOLUTION_ID, MAX_IDENTIFIER_LENGTH);
        }
        problemSummary = text(problemSummary, "verified_solution_refs.problem_summary", 1, MAX_PROBLEM_SUMMARY_CHARS);
        claims = list(claims, MAX_REF_CLAIMS, "verified_solution_refs.claims");
        if (claims.isEmpty()) {
            throw violation("verified_solution_refs.claims", "must not be empty");
        }
        unique(claims, VerifiedSolutionRefClaim::claimId, "verified_solution_refs.claims.claim_id");
        if (solutionId.equals(revisesSolutionId)) {
            throw violation("verified_solution_refs.revises_solution_id", "cannot name the reference itself");
        }
    }
}
