package com.proovy.domain.run.terminal;

import java.util.List;
import java.util.UUID;

/**
 * 수락 판정이 사전 조회 값에 기댄 항목. 저장 transaction(1.82)이 같은 transaction 안에서 다시 확인해야 한다(TOCTOU).
 *
 * @param citedAssets         인용·provenance가 쓴 asset: 아직 ACTIVE이고 content version이 같아야 한다
 * @param referencedSolutions 재표현(existing reference)한 solution: 같은 Note에 있어야 한다
 * @param revisedSolutions    정정 대상: 아직 ACTIVE(superseded 아님)여야 하고, 같은 transaction에서 superseded로 바꾼다
 */
public record TerminalRecheck(
        List<CitedAsset> citedAssets,
        List<UUID> referencedSolutions,
        List<UUID> revisedSolutions) {

    public TerminalRecheck {
        citedAssets = List.copyOf(citedAssets);
        referencedSolutions = List.copyOf(referencedSolutions);
        revisedSolutions = List.copyOf(revisedSolutions);
    }

    public record CitedAsset(long noteAssetId, int contentVersion) {
    }
}
