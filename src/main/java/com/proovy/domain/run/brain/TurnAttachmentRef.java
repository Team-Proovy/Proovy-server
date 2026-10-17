package com.proovy.domain.run.brain;

import static com.proovy.domain.run.brain.InternalRunContract.MAX_DISPLAY_LABEL_CHARS;
import static com.proovy.domain.run.brain.InternalRunContract.opaqueId;
import static com.proovy.domain.run.brain.InternalRunContract.optionalText;
import static com.proovy.domain.run.brain.InternalRunContract.require;
import static com.proovy.domain.run.brain.InternalRunContract.versionLabel;

/** 이전 turn Turn Attachment 참조. signed URL·원본 binary·전사본은 담지 않는다. */
public record TurnAttachmentRef(
        String attachmentId,
        String sourceMessageId,
        String sha256,
        AssetMime mime,
        String pageUnderstandingVersion,
        String imageNormalizationVersion,
        String displayLabel) {

    public TurnAttachmentRef {
        attachmentId = opaqueId(attachmentId, "turn_attachment_refs.attachment_id");
        sourceMessageId = opaqueId(sourceMessageId, "turn_attachment_refs.source_message_id");
        sha256 = InternalRunContract.sha256(sha256, "turn_attachment_refs.sha256");
        require(mime, "turn_attachment_refs.mime");
        pageUnderstandingVersion = versionLabel(pageUnderstandingVersion,
                "turn_attachment_refs.page_understanding_version");
        imageNormalizationVersion = versionLabel(imageNormalizationVersion,
                "turn_attachment_refs.image_normalization_version");
        displayLabel = optionalText(displayLabel, "turn_attachment_refs.display_label", 1, MAX_DISPLAY_LABEL_CHARS);
    }
}
