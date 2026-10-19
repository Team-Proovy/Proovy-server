package com.proovy.domain.run.brain;

import static com.proovy.domain.run.brain.InternalRunContract.CLAIM_ID;
import static com.proovy.domain.run.brain.InternalRunContract.MAX_CLAIM_STATEMENT_CHARS;
import static com.proovy.domain.run.brain.InternalRunContract.MAX_IDENTIFIER_LENGTH;
import static com.proovy.domain.run.brain.InternalRunContract.matching;
import static com.proovy.domain.run.brain.InternalRunContract.require;
import static com.proovy.domain.run.brain.InternalRunContract.text;

/** 참조 Verified Solution의 claim 발췌. */
public record VerifiedSolutionRefClaim(String claimId, String statement, SummaryCoverage coverage) {

    public VerifiedSolutionRefClaim {
        claimId = matching(claimId, "claim.claim_id", CLAIM_ID, MAX_IDENTIFIER_LENGTH);
        statement = text(statement, "claim.statement", 1, MAX_CLAIM_STATEMENT_CHARS);
        require(coverage, "claim.coverage");
    }
}
