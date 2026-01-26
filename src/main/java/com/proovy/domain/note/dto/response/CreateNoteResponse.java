package com.proovy.domain.note.dto.response;

import java.time.LocalDateTime;
import java.util.List;

public record CreateNoteResponse(
        Long noteId,
        String title,
        String titleGeneratedBy,
        Integer conversationLimit,
        FirstConversationDto firstConversation,
        LocalDateTime createdAt
) {
    public record FirstConversationDto(
            Long conversationId,
            UserMessageDto userMessage,
            AssistantMessageDto assistantMessage
    ) {
    }

    public record UserMessageDto(
            Long messageId,
            String content,
            List<MentionedAssetDto> mentionedAssets,
            List<String> mentionedTools,
            LocalDateTime createdAt
    ) {
    }

    public record AssistantMessageDto(
            Long messageId,
            String content,
            java.util.List<String> usedTools,
            String status,
            LocalDateTime createdAt
    ) {
    }

    public record MentionedAssetDto(
            Long assetId,
            String fileName
    ) {
    }
}

