package com.proovy.domain.run.terminal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proovy.domain.asset.entity.AssetStatus;
import com.proovy.domain.run.brain.SummaryCoverage;
import com.proovy.domain.solution.entity.VerifiedSolutionStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 저장 transaction 안에서 {@link TerminalAcceptanceScope}를 DB의 지금 값으로 만든다(사전 조회 scope를 쓰지 않는다).
 * <ul>
 *   <li>asset: {@code run_asset_dependencies} + {@code assets}({@code FOR SHARE} — 판정부터 commit까지 상태·version이
 *       바뀌지 않는다). Brain asset ID는 1.78 snapshot과 같은 {@code String.valueOf(asset id)}.</li>
 *   <li>Page Understanding version: Viewer Focus asset은 {@code viewer_context.page_understanding_version}, 나머지는
 *       {@code policy_snapshot.asset_versions.page_understanding_version}(접수 시 고정).</li>
 *   <li>provenance content version 문자열: Brain wire 철자가 계약에 없다(terminal-acceptance "검증하지 않은 부분").
 *       잠정적으로 dependency의 정수 version을 문자열로 쓴다 — Brain 후속에서 철자를 고정하면 여기만 바꾼다.</li>
 *   <li>solution: {@code request_snapshot.verified_solution_refs}의 (source message, public ID)로 같은 Note의 row를 찾는다.
 *       public ID는 run 안에서만 unique라 message까지 함께 맞춘다. 다른 Note·없는 row는 scope에 들어가지 않는다.</li>
 * </ul>
 * 호출자는 Note({@code FOR SHARE})와 run({@code FOR UPDATE})을 먼저 잠근다. 그래서 Note 삭제 cascade가 solution을 지우지 못한다.
 */
final class TerminalScopeReader {

    private static final String PDF_MIME = "application/pdf";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    TerminalScopeReader(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** scope를 만들 수 없는 데이터 상태(데이터 미준비). 결과를 판정하지 않고 run을 그대로 둔다. */
    static final class ScopeNotReady extends RuntimeException {
        ScopeNotReady(String message) {
            super(message, null, false, false);
        }
    }

    record LockedAsset(long assetId, AssetStatus status, int contentVersion, long noteId) {
    }

    record Scope(TerminalAcceptanceScope acceptance, Map<Long, LockedAsset> lockedAssets) {
    }

    Scope read(UUID runId, String wireRunId, int attempt, String fenceToken, long userId, long noteId,
            JsonNode requestSnapshot, JsonNode policySnapshot) {
        String defaultUnderstanding = policySnapshot.path("asset_versions").path("page_understanding_version")
                .asText(null);
        JsonNode viewer = requestSnapshot.path("viewer_context");
        String viewerAssetId = viewer.path("asset_id").asText(null);
        String viewerUnderstanding = viewer.path("page_understanding_version").asText(null);

        Map<Long, LockedAsset> locked = new LinkedHashMap<>();
        List<ScopedAsset> assets = new ArrayList<>();
        jdbc.query("""
                SELECT a.id, d.content_version, a.user_id, a.note_id, a.status, a.content_version, a.mime_type,
                       a.sha256, a.page_count
                FROM (SELECT asset_id, max(content_version) AS content_version FROM run_asset_dependencies
                      WHERE run_id = ? GROUP BY asset_id) d
                JOIN assets a ON a.id = d.asset_id
                ORDER BY a.id
                FOR SHARE OF a
                """, rs -> {
            long assetId = rs.getLong(1);
            AssetStatus status = AssetStatus.valueOf(rs.getString(5));
            String mime = rs.getString(7);
            Integer pageCount = (Integer) rs.getObject(9);
            if (PDF_MIME.equals(mime) && pageCount == null) {
                // 1.81 ScopedAsset.of와 같은 규칙: page_count 없는 PDF를 1쪽으로 보면 정상 인용이 범위 밖으로 잘못 거부된다.
                throw new ScopeNotReady("pdf dependency " + assetId + " has no page metadata");
            }
            String brainId = String.valueOf(assetId);
            String understanding = brainId.equals(viewerAssetId) && viewerUnderstanding != null
                    ? viewerUnderstanding : defaultUnderstanding;
            if (understanding == null) {
                throw new ScopeNotReady("run snapshot has no page understanding version");
            }
            int dependencyVersion = rs.getInt(2);
            int currentVersion = rs.getInt(6);
            locked.put(assetId, new LockedAsset(assetId, status, currentVersion, rs.getLong(4)));
            assets.add(new ScopedAsset(brainId, assetId, rs.getLong(3), rs.getLong(4), status, dependencyVersion,
                    currentVersion, String.valueOf(dependencyVersion), rs.getString(8), pageCount, understanding));
        }, runId);

        List<ScopedSolution> solutions = new ArrayList<>();
        for (JsonNode ref : requestSnapshot.path("verified_solution_refs")) {
            String publicId = ref.path("solution_id").asText();
            UUID messageId;
            try {
                messageId = UUID.fromString(ref.path("source_message_id").asText());
            } catch (IllegalArgumentException e) {
                continue;
            }
            jdbc.query("""
                    SELECT solution_id, status, revises_solution_id, solution_version, claims::text
                    FROM verified_solutions
                    WHERE note_id = ? AND assistant_message_id = ? AND public_solution_id = ?
                    """, rs -> {
                solutions.add(new ScopedSolution(publicId, rs.getObject(1, UUID.class), noteId,
                        VerifiedSolutionStatus.valueOf(rs.getString(2)), rs.getObject(3, UUID.class), rs.getInt(4),
                        claimCoverage(rs.getObject(1, UUID.class), rs.getString(5))));
            }, noteId, messageId, publicId);
        }

        Map<UUID, UUID> revisionLinks = new HashMap<>();
        jdbc.query("""
                SELECT solution_id, revises_solution_id FROM verified_solutions
                WHERE note_id = ? AND revises_solution_id IS NOT NULL
                """, rs -> {
            revisionLinks.put(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class));
        }, noteId);

        return new Scope(new TerminalAcceptanceScope(wireRunId, attempt, fenceToken, userId, noteId, assets,
                solutions, revisionLinks), locked);
    }

    /** {@link ScopedSolution#of}와 같은 해석. 저장 claim이 손상됐으면 추측하지 않고 드러낸다. */
    private Map<String, SummaryCoverage> claimCoverage(UUID solutionId, String claimsJson) {
        JsonNode claims;
        try {
            claims = objectMapper.readTree(claimsJson);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored verified solution claims are corrupt: " + solutionId);
        }
        Map<String, SummaryCoverage> coverage = new LinkedHashMap<>();
        for (JsonNode claim : claims) {
            String claimId = claim.path("claim_id").asText("");
            String wire = claim.path("coverage").asText("");
            SummaryCoverage value = wire.equals(SummaryCoverage.DIRECT.wireValue()) ? SummaryCoverage.DIRECT
                    : wire.equals(SummaryCoverage.SUPPORTING.wireValue()) ? SummaryCoverage.SUPPORTING : null;
            if (claimId.isEmpty() || value == null) {
                throw new IllegalStateException("stored verified solution claims are corrupt: " + solutionId);
            }
            coverage.put(claimId, value);
        }
        return coverage;
    }
}
