package com.proovy.domain.conversation.controller;

import com.proovy.domain.conversation.dto.request.ConversationRequest;
import com.proovy.domain.conversation.dto.response.ConversationResponse;
import com.proovy.domain.conversation.service.ChatService;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.response.ErrorCode;
import com.proovy.global.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;

@Slf4j
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
@Tag(name = "Conversation", description = "대화 API (Proovy-ai 연동)")
public class ConversationController {

    private final ChatService chatService;

    @PostMapping(produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "대화 생성 (SSE 스트리밍 또는 단건 응답)",
            description = """
                    사용자 기반 대화를 Proovy-ai 에이전트에 전달하고 응답을 받습니다.
                    
                    - isStream=true: SSE 스트리밍으로 실시간 응답 (기본 구현)
                    - isStream=false: 최종 응답만 단건으로 반환 (향후 구현)
                    
                    첨부 자산(mentionedAssetIds)은 S3 URL로 변환되어 전달됩니다.
                    사용자의 가장 최근 활성 채팅 세션을 사용하며, 없으면 새로 생성합니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "대화 생성 성공 (SSE 스트리밍)",
                    content = @Content(mediaType = "text/event-stream")
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "USER4041 - 사용자를 찾을 수 없습니다."
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "CONV4001 - 잘못된 기능 값이 포함되어 있습니다."
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "CONV5001/CONV5002 - Proovy-ai 통신 오류"
            )
    })
    public Flux<ServerSentEvent<String>> createConversation(
            @Parameter(description = "스트리밍 여부 (true: SSE, false: 단건 JSON)", example = "true")
            @RequestParam(name = "isStream", defaultValue = "false") Boolean isStream,

            @Parameter(hidden = true)
            @AuthenticationPrincipal UserPrincipal userDetails,

            @Parameter(description = "테스트용 사용자 ID (인증 없을 때 사용)")
            @RequestParam(name = "userId", required = false) Long userId,

            @Valid @RequestBody ConversationRequest request
    ) {
        Long resolvedUserId = (userDetails != null) ? userDetails.getUserId() : userId;

        if (resolvedUserId == null) {
            throw new BusinessException(ErrorCode.USER4041);
        }

        if (Boolean.FALSE.equals(isStream)) {
            throw new UnsupportedOperationException("invoke 모드는 아직 구현되지 않았습니다. isStream=true 로만 호출해 주세요.");
        }

        log.info("Create conversation - userId: {}, isStream: {}", resolvedUserId, isStream);

        return streamResponse(resolvedUserId, request);
    }

    /**
     * SSE 스트리밍 응답 생성
     */
    private Flux<ServerSentEvent<String>> streamResponse(Long userId, ConversationRequest request) {
        return chatService.streamConversation(userId, request)
                .map(event -> {
                    try {
                        // ProovyAiStreamEvent를 JSON 문자열로 변환
                        String eventType = event.getEvent();
                        String dataJson = convertToJson(event.getData());
                        
                        return ServerSentEvent.<String>builder()
                                .event(eventType)
                                .data(dataJson)
                                .build();
                    } catch (Exception e) {
                        log.error("Failed to build SSE event", e);
                        return ServerSentEvent.<String>builder()
                                .event("error")
                                .data("{\"error\":\"Failed to process event\"}")
                                .build();
                    }
                })
                .concatWith(Flux.just(
                        ServerSentEvent.<String>builder()
                                .event("[DONE]")
                                .data("")
                                .build()
                ))
                .doOnComplete(() -> log.info("SSE stream completed for userId: {}", userId))
                .doOnError(error -> log.error("SSE stream error for userId: {}", userId, error));
    }

    /**
     * Map을 JSON 문자열로 변환
     */
    private String convertToJson(Object data) {
        try {
            if (data == null) {
                return "{}";
            }
            // 간단한 JSON 변환 (Jackson ObjectMapper 사용)
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data);
        } catch (Exception e) {
            log.warn("Failed to convert to JSON", e);
            return "{}";
        }
    }
}
