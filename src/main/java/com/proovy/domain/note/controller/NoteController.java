package com.proovy.domain.note.controller;

import com.proovy.domain.note.dto.request.CreateNoteRequest;
import com.proovy.domain.note.dto.request.UpdateNoteTitleRequest;
import com.proovy.domain.note.dto.response.CreateNoteResponse;
import com.proovy.domain.note.dto.response.DeleteNoteResponse;
import com.proovy.domain.note.dto.response.NoteDetailResponse;
import com.proovy.domain.note.dto.response.NoteListResponse;
import com.proovy.domain.note.dto.response.ToolListResponse;
import com.proovy.domain.note.dto.response.UpdateNoteTitleResponse;
import com.proovy.domain.note.dto.response.AssetListResponse;
import com.proovy.domain.note.service.NoteService;
import com.proovy.global.response.ApiResponse;
import com.proovy.global.security.UserPrincipal;
import com.proovy.global.tool.entity.Tool;
import com.proovy.global.tool.service.ToolService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/notes")
@RequiredArgsConstructor
@Tag(name = "Notes", description = "노트 관리 API")
public class NoteController {

    private final NoteService noteService;
    private final ToolService toolService;

    @PostMapping
    @Operation(
            summary = "새 노트 생성",
            description = """
                    새 노트를 생성합니다.

                    - 이 API는 노트 리소스만 생성하며, 대화/메시지는 생성하지 않습니다.
                    - 요청 본문에 제목을 전달하지 않으면 서버에서 현재 시각을 포함한 더미 제목을 자동으로 생성합니다.

                    **생성 제한 (요금제별)**
                    - Free: 2개
                    - Standard: 10개
                    - Pro: 20개
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "노트 생성 성공",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResponse.class),
                            examples = @ExampleObject(
                                    name = "노트 생성 성공 예시",
                                    summary = "새 노트 생성 성공 응답",
                                    value = """
                                            {
                                                \"isSuccess\": true,
                                                \"code\": \"COMMON201\",
                                                \"message\": \"노트가 생성되었습니다.\",
                                                \"result\": {
                                                    \"noteId\": 1,
                                                    \"title\": \"새 노트 2026-02-09 19:19\",
                                                    \"titleGeneratedBy\": \"SYSTEM\",
                                                    \"conversationLimit\": 50,
                                                    \"firstConversation\": null,
                                                    \"createdAt\": \"2026-02-09T19:19:20.8583209\"
                                                }
                                            }
                                            """
                            )
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청 (유효성 검사 실패 등)"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "노트 생성 한도 초과 (NOTE4031)"
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

    @DeleteMapping("/{noteId}")
    @Operation(
            summary = "노트 및 관련 데이터 삭제",
            description = """
                    노트를 영구적으로 삭제합니다.
                    
                    **삭제되는 데이터**
                    - 노트 정보
                    - 모든 대화 및 메시지 (CASCADE)
                    - S3에 저장된 파일 (원본 + 썸네일)
                    - DB의 자산 레코드
                    
                    **주의**: 삭제된 데이터는 복구할 수 없습니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "노트 삭제 성공"
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
    public ApiResponse<DeleteNoteResponse> deleteNote(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @Parameter(description = "삭제할 노트의 고유 ID", example = "10")
            @PathVariable Long noteId
    ) {
        log.info("노트 삭제 요청 - userId: {}, noteId: {}", userPrincipal.getUserId(), noteId);
        DeleteNoteResponse response = noteService.deleteNote(userPrincipal.getUserId(), noteId);
        return ApiResponse.success("노트 및 관련 대화, 자산 데이터가 모두 삭제되었습니다.", response);
    }

    @GetMapping("/{noteId}")
    @Operation(
            summary = "노트 상세 정보와 대화 내역, 첨부 파일 목록 조회 (채팅방 진입 시 호출)",
            description = """
                    채팅방에 진입할 때 호출하는 API로, 노트의 상세 정보, 대화 내역, 첨부 파일 목록을 조회합니다.
                    
                    **응답 내용**
                    - 노트 기본 정보 (제목, 생성일, 마지막 사용일)
                    - 대화 사용량 정보 (현재 대화 수/제한 수/사용률)
                    - 노트에 첨부된 파일 목록 (OCR 상태 포함)
                    - 대화 내역 (user-assistant 메시지 쌍, 페이징)
                    
                    **페이징**
                    - 대화 내역은 최신순으로 정렬되어 페이징 처리됩니다.
                    - 기본값: page=0, size=20
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "노트 상세 조회 성공"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "노트 접근 권한 없음 (NOTE4031)"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "노트를 찾을 수 없음 (NOTE4041)"
            )
    })
    public ApiResponse<NoteDetailResponse> getNoteDetail(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @Parameter(description = "조회할 노트의 고유 ID", example = "10")
            @PathVariable Long noteId,
            @Parameter(description = "대화 페이지 번호", example = "0")
            @RequestParam(defaultValue = "0") int conversationPage,
            @Parameter(description = "페이지당 대화 수", example = "20")
            @RequestParam(defaultValue = "20") int conversationSize
    ) {
        log.info("노트 상세 조회 요청 - userId: {}, noteId: {}", userPrincipal.getUserId(), noteId);
        NoteDetailResponse response = noteService.getNoteDetail(
                userPrincipal.getUserId(),
                noteId,
                conversationPage,
                conversationSize
        );
        return ApiResponse.success("노트 상세 조회에 성공했습니다.", response);
    }

    @GetMapping("/tools")
    @Operation(
            summary = "사용 가능한 도구 목록 조회 (@멘션용)",
            description = """
                    채팅창에서 `@` 입력 시 표시할 사용 가능한 도구 목록을 조회합니다.
                    
                    **도구 종류**
                    - GRAPH: 그래프 그리기 - 함수 그래프 시각화
                    - SOLUTION: 해설지 생성하기 - 단계별 풀이 과정 생성
                    - VARIATION: 변형 문제 생성하기 - 유사 유형 변형 문제 생성
                    
                    **검색 기능**
                    - query 파라미터로 도구 이름 또는 toolCode 검색 가능 (자동완성용)
                    - 검색어가 없으면 전체 활성화된 도구 목록 반환
                    
                    **사용 예시**
                    - `GET /api/notes/tools` : 전체 도구 목록
                    - `GET /api/notes/tools?query=그래` : "그래프"가 포함된 도구 검색
                    - `GET /api/notes/tools?query=SOLUTION` : toolCode로 검색
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "도구 목록 조회 성공"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 실패 (AUTH4011)"
            )
    })
    public ApiResponse<ToolListResponse> getToolList(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @Parameter(description = "도구 이름 검색어 (자동완성용)", example = "그래프")
            @RequestParam(required = false) String query
    ) {
        log.info("도구 목록 조회 요청 - userId: {}, query: {}",
                userPrincipal.getUserId(), query);

        List<Tool> tools = toolService.getToolList(query);
        ToolListResponse response = ToolListResponse.from(tools);
        return ApiResponse.success("도구 목록 조회에 성공했습니다.", response);
    }

    @GetMapping("/{noteId}/assets")
    @Operation(
            summary = "노트의 파일 목록 조회 (#멘션용)",
            description = """
                    채팅창에서 `#` 입력 시 해당 노트의 파일 목록을 검색하여 자동완성을 제공합니다.
                    
                    **응답 내용**
                    - 노트에 첨부된 파일 목록
                    - 파일명, 크기, MIME 타입, 파일 유형
                    - OCR 처리 상태
                    - 썸네일 URL (있는 경우)
                    
                    **검색 기능**
                    - query 파라미터로 파일명 검색 가능 (자동완성용)
                    - 검색어가 없으면 노트의 전체 파일 목록 반환
                    - 대소문자 구분 없이 파일명에 포함된 문자열로 검색
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "파일 목록 조회 성공"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "노트 접근 권한 없음 (NOTE4031)"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "노트를 찾을 수 없음 (NOTE4041)"
            )
    })
    public ApiResponse<AssetListResponse> getAssetList(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @Parameter(description = "조회할 노트의 고유 ID", example = "10")
            @PathVariable Long noteId,
            @Parameter(description = "파일명 검색어 (자동완성용)", example = "Exogenous")
            @RequestParam(required = false) String query
    ) {
        log.info("노트 파일 목록 조회 요청 - userId: {}, noteId: {}, query: {}",
                userPrincipal.getUserId(), noteId, query);

        AssetListResponse response = noteService.getAssetList(
                userPrincipal.getUserId(),
                noteId,
                query
        );
        return ApiResponse.success("파일 목록 조회에 성공했습니다.", response);
    }
}
