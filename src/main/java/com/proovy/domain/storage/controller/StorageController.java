package com.proovy.domain.storage.controller;

import com.proovy.domain.storage.dto.request.BulkDeleteRequest;
import com.proovy.domain.storage.dto.response.BulkDeleteResponse;
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
            summary = "자산 일괄 삭제",
            description = "체크박스로 선택한 여러 파일을 한 번에 삭제합니다. 최대 30개까지 가능합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "파일을 찾을 수 없음")
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
