package com.proovy.domain.run.brain;

import static com.proovy.domain.run.brain.InternalRunContract.MAX_READ_URL_CHARS;
import static com.proovy.domain.run.brain.InternalRunContract.opaqueId;
import static com.proovy.domain.run.brain.InternalRunContract.text;
import static com.proovy.domain.run.brain.InternalRunContract.versionLabel;

/**
 * 이번 turn의 직접 입력 첨부. {@code readUrl}은 필수지만 fingerprint·로그·{@link #toString()}에서 제외한다.
 */
public record TurnAttachment(
        String attachmentId,
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

    public TurnAttachment {
        attachmentId = opaqueId(attachmentId, "turn_attachments.attachment_id");
        sha256 = InternalRunContract.sha256(sha256, "turn_attachments.sha256");
        ScopedAssetFacts.check("turn_attachments", mime, byteSize, pageCount, pixelWidth, pixelHeight, pixelCount);
        uploadPolicyVersion = versionLabel(uploadPolicyVersion, "turn_attachments.upload_policy_version");
        pageUnderstandingVersion = versionLabel(pageUnderstandingVersion,
                "turn_attachments.page_understanding_version");
        imageNormalizationVersion = versionLabel(imageNormalizationVersion,
                "turn_attachments.image_normalization_version");
        readUrl = text(readUrl, "turn_attachments.read_url", 1, MAX_READ_URL_CHARS);
    }

    /** URL을 바꾼 사본(재전달 시 짧은 read URL 재발급). 의미 필드는 그대로다. */
    public TurnAttachment withReadUrl(String newReadUrl) {
        return new TurnAttachment(attachmentId, sha256, mime, byteSize, pageCount, pixelWidth, pixelHeight,
                pixelCount, uploadPolicyVersion, pageUnderstandingVersion, imageNormalizationVersion, newReadUrl);
    }

    @Override
    public String toString() {
        return "TurnAttachment[attachmentId=" + attachmentId + ", mime=" + mime + ", readUrl=<redacted>]";
    }
}
