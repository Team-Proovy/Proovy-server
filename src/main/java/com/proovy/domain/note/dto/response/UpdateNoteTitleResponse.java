package com.proovy.domain.note.dto.response;

import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record UpdateNoteTitleResponse(
        Long noteId,
        String title,
        LocalDateTime updatedAt
) {
}

