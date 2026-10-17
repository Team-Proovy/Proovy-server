package com.proovy.domain.run.terminal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * terminal 수락 판정 기준: 현재 run의 identity·fence, 소유 user/Note, 승인 asset dependency, 승인 solution 참조.
 * <p>
 * 사전 조회 값이다. 판정 뒤 asset·solution·run 상태가 바뀔 수 있으므로(TOCTOU) 저장 transaction(1.82)이
 * {@link AcceptedCompletion#recheck()} 항목을 다시 확인해야 한다.
 *
 * @param runId      Brain에 보낸 run_id(wire 문자열)
 * @param attempt    현재 attempt
 * @param fenceToken 현재 fence
 * @param assets     run dependency(같은 asset은 한 번만)
 * @param solutions  run 요청이 Brain에 보낸 기존 solution 참조
 * @param revisionLinks 정정 이력 순환 검사용 {@code solution_id → revises_solution_id}(같은 Note에서 아는 만큼). 비어도 된다.
 */
public record TerminalAcceptanceScope(
        String runId,
        int attempt,
        String fenceToken,
        long userId,
        long noteId,
        List<ScopedAsset> assets,
        List<ScopedSolution> solutions,
        Map<UUID, UUID> revisionLinks) {

    public TerminalAcceptanceScope {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(fenceToken, "fenceToken");
        assets = List.copyOf(assets);
        solutions = List.copyOf(solutions);
        revisionLinks = Map.copyOf(revisionLinks);
        if (assets.stream().map(ScopedAsset::brainAssetId).distinct().count() != assets.size()) {
            throw new IllegalArgumentException("scoped assets must have distinct Brain asset IDs");
        }
        if (solutions.stream().map(ScopedSolution::publicSolutionId).distinct().count() != solutions.size()) {
            throw new IllegalArgumentException("scoped solutions must have distinct public IDs");
        }
    }

    /** source 없는 텍스트 run(asset·solution 참조 없음). */
    public static TerminalAcceptanceScope textOnly(String runId, int attempt, String fenceToken, long userId,
            long noteId) {
        return new TerminalAcceptanceScope(runId, attempt, fenceToken, userId, noteId, List.of(), List.of(), Map.of());
    }

    Map<String, ScopedAsset> assetsByBrainId() {
        Map<String, ScopedAsset> out = new HashMap<>();
        assets.forEach(asset -> out.put(asset.brainAssetId(), asset));
        return out;
    }

    Map<String, ScopedSolution> solutionsByPublicId() {
        Map<String, ScopedSolution> out = new HashMap<>();
        solutions.forEach(solution -> out.put(solution.publicSolutionId(), solution));
        return out;
    }
}
