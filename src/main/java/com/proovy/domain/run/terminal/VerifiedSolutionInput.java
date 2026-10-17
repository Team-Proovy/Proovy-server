package com.proovy.domain.run.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.proovy.domain.run.brain.SummaryCoverage;
import java.util.UUID;

/**
 * 새 {@code verified_solutions} row 입력. 모든 claim이 accepted coverage(direct|supporting)를 가지며
 * ({@code claims[{claim_id, statement, coverage}]}), inconclusive·reject claim은 표현할 수 없다.
 * 정답이 담긴 내부 기록이므로 공개 경로(plain text·browser)에 쓰지 않는다.
 *
 * @param publicSolutionId   Brain neutral ID(run 안에서만 unique)
 * @param contentSha256      검증된 verified-solution-v1 artifact의 canonical JSON sha256
 * @param revisesSolutionId  정정 대상 Server solution ID(승인 참조 안의 ACTIVE row) 또는 null
 */
public record VerifiedSolutionInput(
        String publicSolutionId,
        String schemaVersion,
        int solutionVersion,
        String problemStatement,
        JsonNode problemConditions,
        String solutionText,
        JsonNode claims,
        JsonNode stepMarks,
        JsonNode verificationSummary,
        String verificationVersion,
        JsonNode sourceRefIds,
        SummaryCoverage overallCoverage,
        String contentSha256,
        UUID revisesSolutionId) {
}
