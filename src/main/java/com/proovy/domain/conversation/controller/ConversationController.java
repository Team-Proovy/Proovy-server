package com.proovy.domain.conversation.controller;

import com.proovy.domain.conversation.dto.request.CanvasImageUploadRequest;
import com.proovy.domain.conversation.dto.response.CanvasImageUploadResponse;
import com.proovy.domain.conversation.dto.response.ConversationSearchResponse;
import com.proovy.domain.conversation.service.ConversationQueryService;
import com.proovy.global.response.ApiResponse;
import com.proovy.global.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@Slf4j
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
@Tag(name = "Conversation", description = "대화 조회 API (conversationId = noteId)")
public class ConversationController {

    private final ConversationQueryService conversationQueryService;

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
                    
                    노트 제목만 매칭된 결과는 messageId/userMessage/assistantMessage가 null일 수 있습니다.
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
}
