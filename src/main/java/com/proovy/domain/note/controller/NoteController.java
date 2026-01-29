package com.proovy.domain.note.controller;

import com.proovy.domain.note.dto.request.CreateNoteRequest;
import com.proovy.domain.note.dto.request.UpdateNoteTitleRequest;
import com.proovy.domain.note.dto.response.CreateNoteResponse;
import com.proovy.domain.note.dto.response.NoteListResponse;
import com.proovy.domain.note.dto.response.UpdateNoteTitleResponse;
import com.proovy.domain.note.service.NoteService;
import com.proovy.global.response.ApiResponse;
import com.proovy.global.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
                    
                    **생성 제한 (요금제별)**
                    - Free: 2개
                    - Standard: 10개
                    - Pro: 20개
                    
                    **멘션 기능**
                    - `#` 파일 멘션: mentionedAssetIds
                    - `@` 도구 멘션: mentionedToolCodes (SOLUTION, GRAPH, VARIATION)
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
        return ApiResponse.created("노트가 생성되었습니다.", response);
    }

    @GetMapping
    @Operation(
            summary = "노트 목록 조회 (페이지네이션)",
            description = """
                    사용자의 노트 목록을 페이지네이션으로 조회합니다.
                    
                    **정렬 옵션**
                    - `lastUsedAt,desc`: 마지막 사용 시각 기준 최신순 (기본값)
                    - `createdAt,desc`: 생성 시각 기준 최신순
                    - `title,asc`: 제목 기준 오름차순
                    
                    **페이지 크기**
                    - 기본값: 20
                    - 최대값: 50
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "노트 목록 조회 성공"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 토큰 없음/만료 (AUTH4011)"
            )
    })
    public ApiResponse<NoteListResponse> getNoteList(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @Parameter(description = "페이지 번호 (0부터 시작)", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지당 노트 수 (최대 50)", example = "20")
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "정렬 기준", example = "lastUsedAt,desc")
            @RequestParam(defaultValue = "lastUsedAt,desc") String sort
    ) {
        log.info("노트 목록 조회 요청 - userId: {}, page: {}, size: {}, sort: {}",
                userPrincipal.getUserId(), page, size, sort);
        NoteListResponse response = noteService.getNoteList(userPrincipal.getUserId(), page, size, sort);
        return ApiResponse.success("노트 목록 조회에 성공했습니다.", response);
    }

    @PatchMapping("/{noteId}")
    @Operation(
            summary = "노트 제목 변경",
            description = """
                    노트의 제목을 수정합니다.
                    
                    **제목 길이 제한**
                    - 최소 1자, 최대 50자
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "노트 제목 수정 성공"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "제목 비어있음 (NOTE4002), 제목 길이 초과 (NOTE4001)"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "노트 접근 권한 없음 (NOTE4031)"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "노트 없음 (NOTE4041)"
            )
    })
    public ApiResponse<UpdateNoteTitleResponse> updateNoteTitle(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @Parameter(description = "수정할 노트의 고유 ID", example = "10")
            @PathVariable Long noteId,
            @Valid @RequestBody UpdateNoteTitleRequest request
    ) {
        log.info("노트 제목 수정 요청 - userId: {}, noteId: {}", userPrincipal.getUserId(), noteId);
        UpdateNoteTitleResponse response = noteService.updateNoteTitle(
                userPrincipal.getUserId(),
                noteId,
                request
        );
        return ApiResponse.success("노트 제목이 수정되었습니다.", response);
    }
}

