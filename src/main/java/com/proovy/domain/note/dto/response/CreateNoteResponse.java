package com.proovy.domain.note.dto.response;

import java.time.LocalDateTime;

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
            java.util.List<MentionedAssetDto> mentionedAssets,
            java.util.List<String> mentionedTools,
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

