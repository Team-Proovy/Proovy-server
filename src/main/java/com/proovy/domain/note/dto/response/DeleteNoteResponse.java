package com.proovy.domain.note.dto.response;

import lombok.Builder;

@Builder
public record DeleteNoteResponse(
        Long deletedNoteId,
        Integer deletedConversationCount,
        Integer deletedAssetCount,
        Long freedStorageBytes
) {
}

