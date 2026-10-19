package com.proovy.domain.run.brain;

import static com.proovy.domain.run.brain.InternalRunContract.MAX_READ_URL_CHARS;
import static com.proovy.domain.run.brain.InternalRunContract.atLeast;
import static com.proovy.domain.run.brain.InternalRunContract.opaqueId;
import static com.proovy.domain.run.brain.InternalRunContract.optionalText;
import static com.proovy.domain.run.brain.InternalRunContract.require;
import static com.proovy.domain.run.brain.InternalRunContract.versionLabel;
import static com.proovy.domain.run.brain.InternalRunContract.violation;

/**
 * 요청 전송 시점에 고정한 Viewer Focus. {@code readUrl}은 fingerprint·로그·{@link #toString()}에서 제외한다.
 */
public record ViewerContext(
        String assetId,
        Integer page,
        ViewerSelection selection,
        String sha256,
        AssetMime mime,
        Long byteSize,
        Integer pageCount,
        Integer pixelWidth,
        Integer pixelHeight,
        Long pixelCount,
        String uploadPolicyVersion,
        String pageUnderstandingVersion,
        String imageNormalizationVersion,
        String readUrl) {

    public ViewerContext {
        assetId = opaqueId(assetId, "viewer_context.asset_id");
        atLeast(require(page, "viewer_context.page"), 1, "viewer_context.page");
        sha256 = InternalRunContract.sha256(sha256, "viewer_context.sha256");
        ScopedAssetFacts.check("viewer_context", mime, byteSize, pageCount, pixelWidth, pixelHeight, pixelCount);
        uploadPolicyVersion = versionLabel(uploadPolicyVersion, "viewer_context.upload_policy_version");
        pageUnderstandingVersion = versionLabel(pageUnderstandingVersion, "viewer_context.page_understanding_version");
        imageNormalizationVersion = versionLabel(imageNormalizationVersion,
                "viewer_context.image_normalization_version");
        readUrl = optionalText(readUrl, "viewer_context.read_url", 1, MAX_READ_URL_CHARS);
        if (mime.isPdf() ? page > pageCount : page != 1) {
            throw violation("viewer_context.page", "is outside the asset");
        }
    }

    /** URL을 바꾼 사본(재전달 시 짧은 read URL 재발급). 의미 필드는 그대로다. */
    public ViewerContext withReadUrl(String newReadUrl) {
        return new ViewerContext(assetId, page, selection, sha256, mime, byteSize, pageCount, pixelWidth,
                pixelHeight, pixelCount, uploadPolicyVersion, pageUnderstandingVersion, imageNormalizationVersion,
                newReadUrl);
    }

    @Override
    public String toString() {
        return "ViewerContext[assetId=" + assetId + ", page=" + page + ", mime=" + mime
                + ", readUrl=" + (readUrl == null ? "absent" : "<redacted>") + "]";
    }
}
