package com.proovy.domain.note.controller;

import com.proovy.domain.note.dto.request.CreateNoteRequest;
import com.proovy.domain.note.dto.response.CreateNoteResponse;
import com.proovy.domain.note.service.NoteService;
import com.proovy.global.response.ApiResponse;
import com.proovy.global.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/notes")
@RequiredArgsConstructor
@Tag(name = "Notes", description = "노트 관리 API")
public class NoteController {

    private final NoteService noteService;

    @PostMapping
    @Operation(
            summary = "새 노트 생성",
            description = """
                    첫 메시지를 전송하면 자동으로 새 노트가 생성됩니다.
                    
                    노트 제목은 AI가 대화 내용을 요약하여 자동 생성합니다.
                    (현재는 임시로 첫 메시지를 제목으로 사용)
                    
                    **생성 제한**
                    - 무료 사용자: 5개
                    - 프리미엄 사용자: 10개
                    
                    **멘션 기능**
                    - `#` 파일 멘션: mentionedAssetIds
                    - `@` 도구 멘션: mentionedToolCodes (SOLUTION, SUMMARY, QUIZ, TRANSLATOR)
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "노트 생성 성공"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "메시지 내용 없음 (NOTE4002), 메시지 길이 초과 (NOTE4003), 유효하지 않은 도구 코드 (TOOL4001)"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "노트 생성 한도 초과 (NOTE4031)"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "존재하지 않는 파일 (ASSET4041)"
            )
    })
    public ApiResponse<CreateNoteResponse> createNote(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @Valid @RequestBody CreateNoteRequest request
    ) {
        log.info("노트 생성 요청 - userId: {}", userPrincipal.getUserId());
        CreateNoteResponse response = noteService.createNote(userPrincipal.getUserId(), request);
        return ApiResponse.success("COMMON201", "노트가 생성되었습니다.", response);
    }
}

