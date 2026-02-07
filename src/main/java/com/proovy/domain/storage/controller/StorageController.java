package com.proovy.domain.storage.controller;

import com.proovy.domain.storage.dto.request.BulkDeleteRequest;
import com.proovy.domain.storage.dto.response.BulkDeleteResponse;
import com.proovy.domain.storage.dto.response.StorageResponse;
import com.proovy.domain.storage.service.StorageService;
import com.proovy.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.proovy.global.security.UserPrincipal;

@Tag(name = "Storage API", description = "스토리지 관리 관련 API")
@RestController
@RequestMapping("/api/storage")
@RequiredArgsConstructor
public class StorageController {

    private final StorageService storageService;

    @Operation(
            summary = "스토리지 사용량 조회",
            description = "사용자의 전체 스토리지 사용량과 각 노트별 파일 정보를 조회합니다. 노트 제목이나 파일명으로 검색이 가능합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "검색어 길이 오류 (STORAGE4003)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "토큰 미제공 (AUTH4010), 토큰 만료 (AUTH4012), 유효하지 않은 토큰 (AUTH4013)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음 (USER4041)")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<StorageResponse>> getStorageUsage(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @Parameter(description = "검색어 (노트 제목, 파일명 검색, 최소 2자 이상)")
            @RequestParam(required = false) String keyword
    ) {
        StorageResponse response = storageService.getStorageUsage(userPrincipal.getUserId(), keyword);
        return ResponseEntity.ok(ApiResponse.success("조회에 성공했습니다.", response));
    }

    @Operation(
            summary = "자산 일괄 삭제",
            description = "체크박스로 선택한 여러 파일을 한 번에 삭제합니다. 최대 30개까지 가능합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "필수 값 누락 (COMMON400)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "토큰 미제공 (AUTH4010), 토큰 만료 (AUTH4012), 유효하지 않은 토큰 (AUTH4013)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "자산 접근 권한 없음 (STORAGE4031)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "파일을 찾을 수 없음 (STORAGE4001)")
    })
    @DeleteMapping("/assets")
    public ResponseEntity<ApiResponse<BulkDeleteResponse>> bulkDeleteAssets(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @Valid @RequestBody BulkDeleteRequest request
    ) {
        BulkDeleteResponse response = storageService.bulkDeleteAssets(userPrincipal.getUserId(), request);

        String message = response.deletedCount() + "개의 파일이 삭제되었습니다.";
        return ResponseEntity.ok(ApiResponse.success(message, response));
    }
}
