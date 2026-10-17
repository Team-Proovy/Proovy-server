package com.proovy.domain.run.contract;

import java.util.List;

public record UserMessageContent(
        String text,
        String latex,
        List<RequestedTask> requestedTasks,
        List<RequestedOutput> requestedOutputs,
        List<TurnAttachmentView> turnAttachments,
        ViewerFocus viewerFocus
) {
}
