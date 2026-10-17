package com.proovy.domain.note.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "노트 상세 정보 응답 (채팅방 진입 시)")
public class NoteDetailResponse {

    @Schema(description = "노트 ID", example = "10")
    private Long noteId;

    @Schema(description = "노트 제목", example = "이산수학 과제2 3단원")
    private String title;

    @Schema(description = "사용량 정보")
    private UsageInfo usage;

    @Schema(description = "첨부 파일 목록")
    private List<AssetInfo> assets;

    @Schema(description = "대화 목록")
    private List<ConversationInfo> conversations;

    @Schema(description = "대화 페이지 정보")
    private PageInfo conversationPageInfo;

    @Schema(description = "노트 생성 시각")
    private LocalDateTime createdAt;

    @Schema(description = "마지막 사용 시각")
    private LocalDateTime lastUsedAt;

    @Getter
    @Builder
    public static class UsageInfo {
        @Schema(description = "현재 대화 수", example = "12")
        private Integer conversationCount;

        @Schema(description = "대화 제한 수", example = "50")
        private Integer conversationLimit;

        @Schema(description = "대화 사용률 (%)", example = "24")
        private Integer conversationUsagePercent;
    }

    @Getter
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AssetInfo {
        @Schema(description = "자산 ID", example = "3")
        private Long assetId;

        @Schema(description = "파일명", example = "discrete_math_HW2.pdf")
        private String fileName;

        @Schema(description = "파일 타입", example = "PDF")
        private String fileType;

        @Schema(description = "파일 크기 (bytes)", example = "1048576")
        private Long fileSize;

        @Schema(description = "OCR 상태", example = "COMPLETED")
        private String ocrStatus;

        @Schema(description = "썸네일 URL", example = "https://s3.amazonaws.com/proovy/thumbnails/asset_3.png")
        private String thumbnailUrl;

        @Schema(description = "생성 시각")
        private LocalDateTime createdAt;
    }

    @Getter
    @Builder
    public static class ConversationInfo {
        @Schema(description = "사용자 메시지")
        private MessageInfo userMessage;

        @Schema(description = "AI 응답 메시지. 아직 placeholder이거나 없으면 null", nullable = true)
        private MessageInfo assistantMessage;

        @Schema(description = "대화 생성 시각")
        private LocalDateTime createdAt;
    }

    @Getter
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MessageInfo {
        @Schema(description = "메시지 ID (UUID)")
        private String messageId;

        @Schema(description = "메시지 상태 (PENDING, STREAMING, COMPLETED, FAILED, CANCELED)", example = "COMPLETED")
        private String status;

        @Schema(description = "공개 본문 plain text (미완료 응답은 null)", example = "1번 문제 풀어줘", nullable = true)
        private String content;

        @Schema(description = "메시지 생성 시각")
        private LocalDateTime createdAt;
    }

    @Getter
    @Builder
    public static class PageInfo {
        @Schema(description = "현재 페이지 번호", example = "0")
        private Integer page;

        @Schema(description = "페이지당 항목 수", example = "20")
        private Integer size;

        @Schema(description = "전체 항목 수", example = "12")
        private Long totalElements;

        @Schema(description = "전체 페이지 수", example = "1")
        private Integer totalPages;

        @Schema(description = "다음 페이지 존재 여부", example = "false")
        private Boolean hasNext;
    }
}

