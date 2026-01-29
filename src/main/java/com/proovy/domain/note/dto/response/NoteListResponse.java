package com.proovy.domain.note.dto.response;

import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

@Builder
public record NoteListResponse(
        List<NoteDto> notes,
        PageInfo pageInfo
) {
    @Builder
    public record NoteDto(
            Long noteId,
            String title,
            String thumbnailUrl,
            Integer conversationCount,
            Integer conversationLimit,
            Integer conversationUsagePercent,
            Integer assetCount,
            LocalDateTime createdAt,
            LocalDateTime lastUsedAt
    ) {}

    @Builder
    public record PageInfo(
            Integer page,
            Integer size,
            Long totalElements,
            Integer totalPages,
            Boolean hasNext,
            Boolean hasPrevious
    ) {}
}

