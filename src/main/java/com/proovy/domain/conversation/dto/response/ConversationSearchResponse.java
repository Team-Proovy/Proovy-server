package com.proovy.domain.conversation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
@Schema(description = "대화 검색 결과 응답")
public class ConversationSearchResponse {

    private List<ConversationSearchItem> conversations;
    private PageInfo pageInfo;
    private SearchMetadata searchMetadata;

    @Getter
    @Builder
    public static class ConversationSearchItem {
        @Schema(description = "대화 ID (ChatMessage ID)", example = "150")
        private Long conversationId;
        
        @Schema(description = "노트 ID", example = "10")
        private Long noteId;
        private String noteTitle;
        private MessageInfo userMessage;
        private MessageInfo assistantMessage;
        private List<MentionedFile> mentionedFiles;
        private List<String> mentionedTools;
        private Double relevance;
        private LocalDateTime createdAt;
    }

    @Getter
    @Builder
    public static class MessageInfo {
        private String text;
        private String preview;
        private String highlight;
    }

    @Getter
    @Builder
    public static class MentionedFile {
        private Long assetId;
        private String fileName;
    }

    @Getter
    @Builder
    public static class PageInfo {
        private int page;
        private int size;
        private long totalElements;
        private int totalPages;
        private boolean hasNext;
    }

    @Getter
    @Builder
    public static class SearchMetadata {
        private String query;
        private long totalMatches;
        private long searchTimeMs;
    }
}
