package com.proovy.domain.asset.controller;

import com.proovy.domain.asset.dto.request.UploadUrlRequest;
import com.proovy.domain.asset.dto.response.AssetDetailResponse;
import com.proovy.domain.asset.dto.response.DownloadUrlResponse;
import com.proovy.domain.asset.dto.response.UploadConfirmResponse;
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

    @PostMapping("/{assetId}/confirm")
    @Operation(
            summary = "S3 업로드 완료 알림",
            description = """
                    클라이언트가 S3에 파일 업로드를 완료한 후 서버에 알림을 보냅니다.

                    이 API 호출로 OCR 처리가 시작됩니다.

                    **주의사항**:
                    - 반드시 Presigned URL로 S3 업로드를 완료한 후 호출해야 합니다
                    - 중복 호출 시 409 Conflict 에러가 반환됩니다
                    - OCR 처리는 비동기로 진행됩니다 (파일 크기에 따라 수초~수분 소요)
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "업로드 확인 완료, OCR 처리 시작"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "S3에 파일이 업로드되지 않음 (ASSET4007)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패 (AUTH4010, AUTH4012, AUTH4013)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음 (ASSET4031)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "자산을 찾을 수 없음 (ASSET4041)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 확인된 자산 (ASSET4091)")
    })
    public ApiResponse<UploadConfirmResponse> confirmUpload(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @Parameter(description = "자산 ID", required = true)
            @PathVariable Long assetId) {

        UploadConfirmResponse response = assetsService.confirmUpload(userPrincipal.getUserId(), assetId);
        return ApiResponse.success("업로드가 확인되었습니다.", response);
    }

    @GetMapping("/{assetId}")
    @Operation(
            summary = "자산 상세 정보 + OCR 결과 조회",
            description = """
                    특정 자산의 상세 정보와 OCR 결과를 조회합니다.

                    **OCR 상태**:
                    - `pending`: 대기 중
                    - `processing`: 처리 중
                    - `completed`: 완료 (ocrText 포함)
                    - `failed`: 실패

                    **주의사항**:
                    - ocrStatus가 `processing`인 경우 주기적으로 폴링하여 완료 여부 확인 필요
                    - OCR 완료 시에만 ocrText 객체가 포함됩니다
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패 (AUTH4010, AUTH4012, AUTH4013)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음 (ASSET4031)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "자산을 찾을 수 없음 (ASSET4041)")
    })
    public ApiResponse<AssetDetailResponse> getAssetDetail(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @Parameter(description = "자산 ID", required = true)
            @PathVariable Long assetId) {

        AssetDetailResponse response = assetsService.getAssetDetail(userPrincipal.getUserId(), assetId);
        return ApiResponse.success("조회에 성공했습니다.", response);
    }

    @DeleteMapping("/{assetId}")
    @Operation(
            summary = "자산 삭제",
            description = """
                    특정 자산을 삭제합니다.

                    **삭제 대상**:
                    - S3 원본 파일
                    - S3 썸네일 (있는 경우)
                    - OCR 데이터
                    - DB 레코드

                    **주의사항**:
                    - 삭제된 파일은 복구할 수 없습니다
                    - 삭제 시 스토리지 용량이 즉시 반환됩니다
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패 (AUTH4010, AUTH4012, AUTH4013)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "삭제 권한 없음 (ASSET4031)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "자산을 찾을 수 없음 (ASSET4041)")
    })
    public ApiResponse<Void> deleteAsset(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @Parameter(description = "자산 ID", required = true)
            @PathVariable Long assetId) {

        assetsService.deleteAsset(userPrincipal.getUserId(), assetId);
        return ApiResponse.success("삭제되었습니다.", null);
    }
}
