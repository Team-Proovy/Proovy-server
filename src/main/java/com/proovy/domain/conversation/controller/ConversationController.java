package com.proovy.domain.conversation.controller;

import com.proovy.domain.conversation.dto.request.CanvasImageUploadRequest;
import com.proovy.domain.conversation.dto.request.ConversationRequest;
import com.proovy.domain.conversation.dto.response.CanvasImageUploadResponse;
import com.proovy.domain.conversation.dto.response.ConversationDetailResponse;
import com.proovy.domain.conversation.dto.response.ConversationResponse;
import com.proovy.domain.conversation.dto.response.ConversationSearchResponse;
import com.proovy.domain.conversation.service.ChatService;
import com.proovy.domain.conversation.service.ConversationQueryService;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.response.ApiResponse;
import com.proovy.global.response.ErrorCode;
import com.proovy.global.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.LocalDate;

@Slf4j
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
@Tag(name = "Conversation", description = "대화 API (Proovy-ai 연동)")
public class ConversationController {

    private final ChatService chatService;
    private final ConversationQueryService conversationQueryService;

    // 정상 응답은 text/event-stream 으로 보내되,
    // 예외(GlobalExceptionHandler)는 application/json 으로 내려갈 수 있도록 JSON 도 허용한다.
    @PostMapping(produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
    @Operation(
            summary = "대화 생성 (SSE 스트리밍 또는 단건 응답)",
            description = """
                    사용자 기반 대화를 Proovy-ai 에이전트에 전달하고 응답을 받습니다.
                    
                    - isStream=true: SSE 스트리밍으로 실시간 응답 (기본 구현)
                    - isStream=false: 최종 응답만 단건으로 반환 (향후 구현)
                    
                    첨부 자산(mentionedAssetIds)은 S3 URL로 변환되어 전달됩니다.
                    메시지는 ChatSession과 Note에 모두 연결되어 저장됩니다.
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
            @RequestParam(name = "isStream", defaultValue = "true") Boolean isStream,

            @Parameter(hidden = true)
            @AuthenticationPrincipal UserPrincipal userPrincipal,

            @Parameter(hidden = true)
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,

            @Valid @RequestBody ConversationRequest request
    ) {
        Long resolvedUserId = userPrincipal.getUserId();

        // Authorization 헤더에서 Bearer 토큰 추출
        String accessToken = extractBearerToken(authorizationHeader);

        if (Boolean.FALSE.equals(isStream)) {
            throw new UnsupportedOperationException("invoke 모드는 아직 구현되지 않았습니다. isStream=true 로만 호출해 주세요.");
        }

        log.info("Create conversation - userId: {}, isStream: {}", resolvedUserId, isStream);

        return streamResponse(resolvedUserId, request, accessToken);
    }

    /**
     * Authorization 헤더에서 Bearer 토큰 추출
     */
    private String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            return authorizationHeader.substring(7);
        }
        return null;
    }

    /**
     * SSE 스트리밍 응답 생성
     */
    private Flux<ServerSentEvent<String>> streamResponse(Long userId, ConversationRequest request, String accessToken) {
        return chatService.streamConversation(userId, request, accessToken)
                .map(event -> {
                    try {
                        // V2: SSE 표준 event 필드를 사용하여 이벤트를 구분합니다.
                        // 프론트엔드에서는 addEventListener(eventType, ...)로 수신해야 합니다.
                        String eventType = event.getEvent();
                        
                        // 데이터 Payload JSON 변환
                        Object payload = event.getData() != null ? event.getData() : java.util.Collections.emptyMap();
                        String dataJson = convertToJson(payload);

                        return ServerSentEvent.<String>builder()
                                .event(eventType) // SSE 표준 event 헤더 설정
                                .data(dataJson)
                                .build();
                    } catch (Exception e) {
                        log.error("Failed to build SSE event", e);
                        return ServerSentEvent.<String>builder()
                                .event("error")
                                .data("{\"message\":\"Failed to process event\"}")
                                .build();
                    }
                })
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

    /**
     * 캔버스 이미지 업로드
     */
    @PostMapping("/canvas-images")
    @Operation(
            summary = "캔버스 이미지 업로드",
            description = "캔버스에서 그린 이미지를 S3에 업로드하기 위한 Presigned URL을 발급합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "캔버스 이미지 업로드 URL 발급 성공"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다."
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "NOTE4041 - 노트를 찾을 수 없습니다."
            )
    })
    public ApiResponse<CanvasImageUploadResponse> uploadCanvasImage(
            @Parameter(hidden = true)
            @AuthenticationPrincipal UserPrincipal userPrincipal,

            @Valid @RequestBody CanvasImageUploadRequest request
    ) {
        Long userId = userPrincipal.getUserId();
        log.info("Canvas image upload request - userId: {}, noteId: {}", userId, request.getNoteId());

        CanvasImageUploadResponse response = conversationQueryService.uploadCanvasImage(userId, request);
        return ApiResponse.success("캔버스 이미지 업로드에 성공했습니다.", response);
    }

    /**
     * 대화 검색
     */
    @GetMapping("/search")
    @Operation(
            summary = "대화 검색",
            description = """
                    사용자의 모든 노트에서 대화를 검색합니다. PostgreSQL Full-Text Search를 활용합니다.
                    
                    노트 제목만 매칭된 결과는 conversationId/userMessage/assistantMessage가 null일 수 있습니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "검색 성공"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "STORAGE4003 - 검색어는 최소 2자 이상부터 입력 가능합니다."
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다."
            )
    })
    public ApiResponse<ConversationSearchResponse> searchConversations(
            @Parameter(hidden = true)
            @AuthenticationPrincipal UserPrincipal userPrincipal,

            @Parameter(description = "검색 키워드 (최소 2자)", required = true)
            @RequestParam String query,

            @Parameter(description = "특정 노트 내에서만 검색")
            @RequestParam(required = false) Long noteId,

            @Parameter(description = "사용된 도구로 필터링 (graph, solution, canvas, code_verify)")
            @RequestParam(required = false) String toolCode,

            @Parameter(description = "검색 시작일 (ISO 8601 형식)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,

            @Parameter(description = "검색 종료일 (ISO 8601 형식)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,

            @Parameter(description = "페이지 번호 (0부터 시작)")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "페이지 크기 (최대 100)")
            @RequestParam(defaultValue = "20") int size
    ) {
        Long userId = userPrincipal.getUserId();
        log.info("Conversation search - userId: {}, query: {}", userId, query);

        // 입력값 검증 및 정규화
        page = Math.max(0, page);
        size = Math.max(1, Math.min(size, 100));
        Pageable pageable = PageRequest.of(page, size);

        ConversationSearchResponse response = conversationQueryService.searchConversations(
                userId, query, noteId, toolCode, startDate, endDate, pageable
        );
        return ApiResponse.success("조회에 성공했습니다.", response);
    }

    /**
     * 대화 상세 조회
     */
    @GetMapping("/{conversationId}")
    @Operation(
            summary = "대화 상세 조회",
            description = """
                    특정 메시지의 상세 내용을 조회합니다.
                    
                    **주의**: conversationId는 ChatMessage ID를 의미합니다.
                    검색 결과나 노트 상세에서 반환된 conversationId를 사용하세요.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다."
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "CONV4031 - 해당 대화에 접근할 권한이 없습니다."
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "CONV4042 - 대화를 찾을 수 없습니다."
            )
    })
    public ApiResponse<ConversationDetailResponse> getConversationDetail(
            @Parameter(hidden = true)
            @AuthenticationPrincipal UserPrincipal userPrincipal,

            @Parameter(description = "대화 ID", required = true)
            @PathVariable Long conversationId
    ) {
        Long userId = userPrincipal.getUserId();
        log.info("Get conversation detail - userId: {}, conversationId: {}", userId, conversationId);

        ConversationDetailResponse response = conversationQueryService.getConversationDetail(userId, conversationId);
        return ApiResponse.success("조회에 성공했습니다.", response);
    }
}
