package com.proovy.domain.run.snapshot;

import com.proovy.domain.run.brain.AssetMime;
import com.proovy.domain.run.brain.InternalRunContractViolation;
import com.proovy.domain.run.brain.ViewerContext;
import com.proovy.domain.run.brain.ViewerSelection;
import com.proovy.domain.run.contract.PageSelection;
import com.proovy.domain.run.contract.ViewerFocus;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * S09 전 기본 자산 사실 구현 (2.18/2.19 최소 선행으로 이미지 pixel 사실 지원).
 * <ul>
 *   <li>Viewer Focus: {@code assets} 행의 확정 metadata(ACTIVE, 같은 사용자·Note, sha256,
 *       PDF {@code page_count} 또는 이미지 {@code pixel_width/height/count})만 쓴다.
 *       적용 version은 접수 시점 정책 값이다. pixel 사실이 없는 이미지는 받지 않는다(RUN4221).</li>
 *   <li>현재 Turn Attachment: 업로드 staging 경로가 아직 없으므로 비어 있지 않으면 RUN4221이다.</li>
 * </ul>
 * S09가 전체 첨부 staging을 만들면 이 구현을 확장·교체한다.
 */
@Component
public class StoredRunAssetFacts implements RunAssetFacts {

    private final NamedParameterJdbcTemplate jdbc;
    private final RunSnapshotProperties properties;

    public StoredRunAssetFacts(NamedParameterJdbcTemplate jdbc, RunSnapshotProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    private record AssetRow(long id, String mimeType, long fileSize, String sha256, Integer pageCount,
                            Integer pixelWidth, Integer pixelHeight, Long pixelCount, int contentVersion) {
    }

    @Override
    public ViewerAsset viewerAsset(long userId, long noteId, ViewerFocus focus) {
        long assetId = parseAssetId(focus.noteAssetId());
        AssetRow asset = jdbc.query("""
                        SELECT id, mime_type, file_size, sha256, page_count,
                               pixel_width, pixel_height, pixel_count, content_version
                        FROM assets
                        WHERE id = :assetId AND note_id = :noteId AND user_id = :userId AND status = 'ACTIVE'
                        """, Map.of("assetId", assetId, "noteId", noteId, "userId", userId),
                (rs, i) -> new AssetRow(
                        rs.getLong("id"),
                        rs.getString("mime_type"),
                        rs.getLong("file_size"),
                        rs.getString("sha256"),
                        (Integer) rs.getObject("page_count"),
                        (Integer) rs.getObject("pixel_width"),
                        (Integer) rs.getObject("pixel_height"),
                        (Long) rs.getObject("pixel_count"),
                        rs.getInt("content_version")))
                .stream().findFirst()
                .orElseThrow(RunSnapshotRejectedException::referenceUnavailable);
        AssetMime mime = mime(asset.mimeType()).orElseThrow(RunSnapshotRejectedException::referenceUnavailable);
        if (asset.sha256() == null) {
            throw RunSnapshotRejectedException.referenceUnavailable();
        }
        RunSnapshotProperties.AssetVersions versions = properties.assetVersions();
        try {
            ViewerContext context;
            if (mime.isPdf()) {
                if (asset.pageCount() == null || focus.page() > asset.pageCount()) {
                    throw RunSnapshotRejectedException.referenceUnavailable();
                }
                context = new ViewerContext(String.valueOf(asset.id()), focus.page(),
                        selection(focus.selection()), asset.sha256(), mime, asset.fileSize(), asset.pageCount(),
                        null, null, null, versions.uploadPolicyVersion(), versions.pageUnderstandingVersion(),
                        versions.imageNormalizationVersion(), null);
            } else {
                if (focus.page() != 1
                        || asset.pixelWidth() == null || asset.pixelHeight() == null || asset.pixelCount() == null
                        || asset.fileSize() > properties.turnAttachments().maxImageSize().toBytes()
                        || asset.pixelCount() > properties.turnAttachments().maxImagePixels()) {
                    throw RunSnapshotRejectedException.referenceUnavailable();
                }
                context = new ViewerContext(String.valueOf(asset.id()), focus.page(),
                        selection(focus.selection()), asset.sha256(), mime, asset.fileSize(), null,
                        asset.pixelWidth(), asset.pixelHeight(), asset.pixelCount(),
                        versions.uploadPolicyVersion(), versions.pageUnderstandingVersion(),
                        versions.imageNormalizationVersion(), null);
            }
            return new ViewerAsset(asset.id(), asset.contentVersion(), context);
        } catch (InternalRunContractViolation e) {
            // 저장된 metadata가 wire 한도 밖이면(크기·page 수·pixel 수) 이 run에서 쓸 수 없는 자산이다.
            throw RunSnapshotRejectedException.referenceUnavailable();
        }
    }

    @Override
    public List<CurrentAttachment> currentAttachments(long userId, long noteId, List<UUID> attachmentIds) {
        if (attachmentIds.isEmpty()) {
            return List.of();
        }
        throw RunSnapshotRejectedException.referenceUnavailable();
    }

    static ViewerSelection selection(PageSelection selection) {
        return selection == null ? null
                : new ViewerSelection(selection.left(), selection.top(), selection.right(), selection.bottom());
    }

    static Optional<AssetMime> mime(String wire) {
        return Arrays.stream(AssetMime.values()).filter(m -> m.wireValue().equals(wire)).findFirst();
    }

    private static long parseAssetId(String noteAssetId) {
        // Note Asset ID는 assets.id(양의 10진수)다. 다른 형태의 opaque ID는 이 Note의 자산이 아니다.
        if (noteAssetId == null || !noteAssetId.matches("[1-9][0-9]{0,18}")) {
            throw RunSnapshotRejectedException.referenceUnavailable();
        }
        try {
            return Long.parseLong(noteAssetId);
        } catch (NumberFormatException e) {
            throw RunSnapshotRejectedException.referenceUnavailable();
        }
    }
}
