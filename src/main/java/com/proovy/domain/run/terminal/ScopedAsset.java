package com.proovy.domain.run.terminal;

import com.proovy.domain.asset.entity.Asset;
import com.proovy.domain.asset.entity.AssetStatus;
import com.proovy.domain.run.entity.RunAssetDependency;
import java.util.Objects;

/**
 * run이 생성 시 고정한 asset dependency 하나와 그 asset의 현재 제품 metadata.
 * <p>
 * {@code brain*} 값은 이 run의 Brain 요청 snapshot이 보낸 wire 철자다(asset ID·content version·Page Understanding
 * version). Server 정수 ID·version을 어떤 문자열로 보내는지는 snapshot 매핑(1.78)이 정하므로, 호출자가 snapshot에서
 * 그대로 옮겨 넣는다. 이 type은 metadata 대조용이며 실제 page 내용이나 인용 의미를 보장하지 않는다.
 *
 * @param dependencyContentVersion run 생성 시 {@code run_asset_dependencies}에 고정한 version
 * @param currentContentVersion    지금 {@code assets.content_version}
 * @param pageCount                PDF page 수. 이미지 등 page metadata가 없으면 null(1 page로 본다)
 */
public record ScopedAsset(
        String brainAssetId,
        long noteAssetId,
        long ownerUserId,
        long noteId,
        AssetStatus status,
        int dependencyContentVersion,
        int currentContentVersion,
        String brainContentVersion,
        String sha256,
        Integer pageCount,
        String pageUnderstandingVersion) {

    public ScopedAsset {
        Objects.requireNonNull(brainAssetId, "brainAssetId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(brainContentVersion, "brainContentVersion");
        Objects.requireNonNull(pageUnderstandingVersion, "pageUnderstandingVersion");
    }

    /** dependency row·asset row와 snapshot wire 값으로 만든다. dependency와 asset은 같은 asset이어야 한다. */
    public static ScopedAsset of(RunAssetDependency dependency, Asset asset, String brainAssetId,
            String brainContentVersion, String pageUnderstandingVersion) {
        if (!dependency.getAssetId().equals(asset.getId())) {
            throw new IllegalArgumentException("dependency and asset must name the same asset");
        }
        return new ScopedAsset(brainAssetId, asset.getId(), asset.getUserId(), asset.getNoteId(), asset.getStatus(),
                dependency.getContentVersion(), asset.getContentVersion(), brainContentVersion, asset.getSha256(),
                asset.getPageCount(), pageUnderstandingVersion);
    }

    int lastPage() {
        return pageCount == null ? 1 : pageCount;
    }
}
