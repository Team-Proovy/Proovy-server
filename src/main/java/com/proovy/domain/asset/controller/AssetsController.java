package com.proovy.domain.asset.controller;

import com.proovy.domain.asset.dto.request.UploadUrlRequest;
import com.proovy.domain.asset.dto.response.DownloadUrlResponse;
import com.proovy.domain.asset.dto.response.UploadUrlResponse;
import com.proovy.domain.asset.service.AssetsService;
import com.proovy.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.proovy.global.security.UserPrincipal;

@RestController
@RequestMapping("/api/assets")
@RequiredArgsConstructor
@Tag(name = "Assets", description = "자산(파일) 관리 API")
public class AssetsController {

    private final AssetsService assetsService;

    @PostMapping("/upload-url")
    @Operation(
            summary = "업로드용 Presigned URL 발급",
            description = """
                    S3 직접 업로드를 위한 Presigned URL을 발급합니다.

                    **지원 파일 형식**: PDF, PNG, JPEG, WEBP
                    - application/pdf
                    - image/png
                    - image/jpeg
                    - image/webp

                    **최대 파일 크기**: 30MB (31,457,280 bytes)

                    **URL 유효 시간**: 15분
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "URL 발급 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "지원하지 않는 파일 형식 (ASSET4001), 파일 크기 초과 (ASSET4002), 잘못된 파일명 (ASSET4005)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "토큰 미제공 (AUTH4010), 토큰 만료 (AUTH4012), 유효하지 않은 토큰 (AUTH4013)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "노트 접근 권한 없음 (NOTE4031), 스토리지 용량 초과 (STORAGE4005)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "노트를 찾을 수 없음 (NOTE4041)")
    })
    public ApiResponse<UploadUrlResponse> generateUploadUrl(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @Valid @RequestBody UploadUrlRequest request) {

        UploadUrlResponse response = assetsService.generateUploadUrl(userPrincipal.getUserId(), request);
        return ApiResponse.success("업로드 URL이 발급되었습니다.", response);
    }

    @GetMapping("/{assetId}/download")
    @Operation(
            summary = "다운로드용 Presigned URL 발급",
            description = """
                    파일 다운로드를 위한 Presigned URL을 발급합니다.

                    클라이언트는 이 URL로 직접 파일을 다운로드할 수 있습니다.

                    **URL 유효 시간**: 15분

                    **Content-Disposition**: 브라우저에서 파일명이 자동 설정됩니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "URL 발급 성공"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "권한 없음 (ASSET4031)"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "자산을 찾을 수 없음 (ASSET4041)"
            )
    })
    public ApiResponse<DownloadUrlResponse> generateDownloadUrl(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @Parameter(description = "자산 ID", required = true)
            @PathVariable Long assetId) {

        DownloadUrlResponse response = assetsService.generateDownloadUrl(userPrincipal.getUserId(), assetId);
        return ApiResponse.success("다운로드 URL이 발급되었습니다.", response);
    }
}
