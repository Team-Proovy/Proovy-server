package com.proovy.domain.run.snapshot;

import com.proovy.domain.run.brain.AssetMime;
import com.proovy.domain.run.brain.ViewerContext;
import com.proovy.domain.run.contract.ViewerFocus;
import java.util.List;
import java.util.UUID;

/**
 * 전송 시점 자산 사실을 확인하는 경계. 실제 업로드 확정·scoped resolver는 S09/S10이 이 인터페이스를 구현한다.
 * 기본 구현은 {@link StoredRunAssetFacts}다.
 * <p>
 * 두 메서드 모두 같은 사용자·Note에서 지금 쓸 수 있는 자산만 받아들이고, 아니면
 * {@link RunSnapshotRejectedException}(RUN4221)을 던진다. 돌려주는 값에는 read URL이 없다.
 */
public interface RunAssetFacts {

    /** Viewer Focus의 Note Asset 사실(readUrl 없음)과 의존성에 고정할 content version. */
    ViewerAsset viewerAsset(long userId, long noteId, ViewerFocus focus);

    /** 이번 turn에 직접 붙인 첨부의 검증된 media 사실. 입력 순서를 지킨다. */
    List<CurrentAttachment> currentAttachments(long userId, long noteId, List<UUID> attachmentIds);

    record ViewerAsset(long assetId, int contentVersion, ViewerContext context) {
    }

    /** 현재 turn 첨부의 사실. URL은 전달 직전에 {@link RunAssetAccess}로 붙는다. */
    record CurrentAttachment(UUID attachmentId, String sha256, AssetMime mime, long byteSize, Integer pageCount,
                             Integer pixelWidth, Integer pixelHeight, Long pixelCount, String uploadPolicyVersion,
                             String pageUnderstandingVersion, String imageNormalizationVersion) {
    }
}
