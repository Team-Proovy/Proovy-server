package com.proovy.domain.conversation.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "대화 검색 결과 응답")
public class ConversationSearchResponse {

    private List<ConversationSearchItem> conversations;
    private PageInfo pageInfo;
    private SearchMetadata searchMetadata;

    @Getter
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ConversationSearchItem {
        @Schema(description = "대화 ID (ChatMessage ID). 제목만 매칭된 결과에서는 null", example = "150", nullable = true)
        private Long conversationId;
        
        @Schema(description = "노트 ID", example = "10")
        private Long noteId;
        private String noteTitle;
        @Schema(description = "사용자 메시지 매칭 정보. 제목만 매칭된 결과에서는 null", nullable = true)
        private MessageInfo userMessage;
        @Schema(description = "어시스턴트 메시지 매칭 정보. 제목만 매칭된 결과에서는 null", nullable = true)
        private MessageInfo assistantMessage;
        private List<MentionedFile> mentionedFiles;
        private List<String> mentionedTools;
        private Double relevance;
        @Schema(description = "결과 기준 시각. 제목만 매칭된 결과에서는 노트 생성 시각", nullable = true)
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
