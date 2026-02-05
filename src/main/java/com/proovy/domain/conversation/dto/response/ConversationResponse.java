package com.proovy.domain.conversation.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 단건 응답용 DTO (isStream=false)
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "대화 응답")
public class ConversationResponse {

    @Schema(description = "대화 ID", example = "1")
    private Long conversationId;

    @Schema(description = "메시지 ID", example = "10")
    private Long messageId;

    @Schema(description = "AI 응답 내용")
    private String content;

    @Schema(description = "Proovy-ai 스레드 ID", example = "thread_abc123")
    private String threadId;

    @Schema(description = "추가 메타데이터")
    private Map<String, Object> metadata;
}
