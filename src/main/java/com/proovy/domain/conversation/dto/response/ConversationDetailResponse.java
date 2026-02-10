package com.proovy.domain.conversation.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class ConversationDetailResponse {

    private Long conversationId;
    private NoteInfo note;
    private UserMessageDetail userMessage;
    private AssistantMessageDetail assistantMessage;
    private List<AiRunInfo> aiRuns;
    private CreditUsedInfo creditUsed;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Getter
    @Builder
    public static class NoteInfo {
        private Long noteId;
        private String title;
    }

    @Getter
    @Builder
    public static class UserMessageDetail {
        private Long messageId;
        private String text;
        private String latex;
        private List<MentionedFileDetail> mentionedFiles;
        private List<String> mentionedTools;
        private List<CanvasImageInfo> canvasImages;
        private LocalDateTime createdAt;
    }

    @Getter
    @Builder
    public static class AssistantMessageDetail {
        private Long messageId;
        private String text;
        private List<SectionInfo> sections;
        private CodeExecutionInfo codeExecution;
        private GeneratedProblemInfo generatedProblem;
        private LocalDateTime createdAt;
    }

    @Getter
    @Builder
    public static class MentionedFileDetail {
        private Long assetId;
        private String fileName;
        private String thumbnailUrl;
    }

    @Getter
    @Builder
    public static class CanvasImageInfo {
        private Long assetId;
        private String previewUrl;
    }

    @Getter
    @Builder
    public static class SectionInfo {
        private String type;
        private String title;
        private String content;
    }

    @Getter
    @Builder
    public static class CodeExecutionInfo {
        private String status;
        private String message;
    }

    @Getter
    @Builder
    public static class GeneratedProblemInfo {
        private String title;
        private String content;
        private String latex;
    }

    @Getter
    @Builder
    public static class AiRunInfo {
        private Long aiRunId;
        private String runType;
        private String modelName;
        private String status;
        private Integer promptTokens;
        private Integer completionTokens;
        private Long latencyMs;
    }

    @Getter
    @Builder
    public static class CreditUsedInfo {
        private Integer amount;
        private List<CreditBreakdown> breakdown;
    }

    @Getter
    @Builder
    public static class CreditBreakdown {
        private String reason;
        private Integer amount;
    }
}
