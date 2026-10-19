package com.proovy.domain.credit.controller;

import com.proovy.domain.credit.dto.response.CreditBalanceResponse;
import com.proovy.domain.credit.dto.response.CreditHistoryResponse;
import com.proovy.domain.credit.service.CreditHistoryService;
import com.proovy.domain.credit.service.CreditUseService;
import com.proovy.global.response.ApiResponse;
import com.proovy.global.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 크레딧 잔액·내역 조회 API. 브라우저가 사용량을 직접 차감하는 API는 두지 않는다.
 * 일반 AI 요청 사용량은 서버 접수 transaction의 {@code RequestUsageService}만 차감·보상한다(ADR 0015).
 */
@Slf4j
@RestController
@RequestMapping("/api/credits")
@RequiredArgsConstructor
@Tag(name = "Credit", description = "크레딧 관련 API")
public class CreditController {

    private final CreditHistoryService creditHistoryService;
    private final CreditUseService creditUseService;

    @GetMapping("/history")
    @Operation(
            operationId = "01_getCreditHistory",
            summary = "크레딧 상태 요약 및 내역 조회",
            description = "사용자의 현재 크레딧 상태 요약과 크레딧 사용/지급/소멸 내역을 페이지네이션하여 조회합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = CreditHistoryResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청 (날짜 형식 오류, 조회 기간 초과)",
                    content = @Content(schema = @Schema(example = "{\"isSuccess\":false,\"code\":\"CREDIT4001\",\"message\":\"날짜 형식이 올바르지 않습니다.\"}"))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 실패",
                    content = @Content(schema = @Schema(example = "{\"isSuccess\":false,\"code\":\"AUTH4013\",\"message\":\"유효하지 않은 토큰입니다.\"}"))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "사용자 없음",
                    content = @Content(schema = @Schema(example = "{\"isSuccess\":false,\"code\":\"USER4041\",\"message\":\"사용자를 찾을 수 없습니다.\"}"))
            )
    })
    public ResponseEntity<ApiResponse<CreditHistoryResponse>> getCreditHistory(
            @AuthenticationPrincipal UserPrincipal userPrincipal,

            @Parameter(description = "페이지 번호 (0부터 시작)", example = "0")
            @RequestParam(required = false) Integer page,

            @Parameter(description = "페이지당 항목 수 (1~100)", example = "20")
            @RequestParam(required = false) Integer size,

            @Parameter(description = "변동 유형 필터 (all, earn, spend, expire)", example = "all")
            @RequestParam(required = false, defaultValue = "all") String changeType,

            @Parameter(description = "크레딧 유형 필터 (all, daily, free, paid)", example = "all")
            @RequestParam(required = false, defaultValue = "all") String creditType,

            @Parameter(description = "조회 시작일 (YYYY-MM-DD)", example = "2026-01-01")
            @RequestParam(required = false) String startDate,

            @Parameter(description = "조회 종료일 (YYYY-MM-DD, 최대 90일)", example = "2026-01-31")
            @RequestParam(required = false) String endDate
    ) {
        Long userId = userPrincipal.getUserId();

        CreditHistoryResponse response = creditHistoryService.getCreditHistory(
                userId, page, size, changeType, creditType, startDate, endDate
        );

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/balance")
    @Operation(
            operationId = "03_getCreditBalance",
            summary = "크레딧 잔액 조회",
            description = "현재 사용자의 크레딧 잔액을 조회합니다. checkCost 파라미터를 통해 특정 비용 사용 가능 여부도 확인할 수 있습니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = CreditBalanceResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 실패",
                    content = @Content(schema = @Schema(example = "{\"isSuccess\":false,\"code\":\"AUTH4013\",\"message\":\"유효하지 않은 토큰입니다.\"}"))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "사용자 없음",
                    content = @Content(schema = @Schema(example = "{\"isSuccess\":false,\"code\":\"USER4041\",\"message\":\"사용자를 찾을 수 없습니다.\"}"))
            )
    })
    public ResponseEntity<ApiResponse<CreditBalanceResponse>> getCreditBalance(
            @AuthenticationPrincipal UserPrincipal userPrincipal,

            @Parameter(description = "사용 가능 여부를 확인할 비용 (선택)", example = "10")
            @RequestParam(required = false) Integer checkCost
    ) {
        Long userId = userPrincipal.getUserId();
        CreditBalanceResponse response = creditUseService.getBalance(userId, checkCost);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
