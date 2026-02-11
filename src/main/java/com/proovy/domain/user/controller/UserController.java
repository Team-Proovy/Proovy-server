package com.proovy.domain.user.controller;

import com.proovy.domain.user.dto.request.UpgradePlanRequest;
import com.proovy.domain.user.dto.response.DeleteUserResponse;
import com.proovy.domain.user.dto.response.MyProfileResponse;
import com.proovy.domain.user.dto.response.SubscriptionResponse;
import com.proovy.domain.user.service.SubscriptionService;
import com.proovy.domain.user.service.UserService;
import com.proovy.global.response.ApiResponse;
import com.proovy.global.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Tag(name = "User", description = "사용자 관련 API")
public class UserController {

    private final UserService userService;
    private final SubscriptionService subscriptionService;

    @GetMapping("/me")
    @Operation(
            operationId = "01_getMyProfile",
            summary = "내 프로필 조회",
            description = "현재 로그인한 사용자의 프로필 정보를 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패 (AUTH4013)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자 없음 (USER4041)")
    })
    public ResponseEntity<ApiResponse<MyProfileResponse>> getMyProfile(
            @AuthenticationPrincipal UserPrincipal userPrincipal
    ) {
        Long userId = userPrincipal.getUserId();
        MyProfileResponse response = userService.getMyProfile(userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/me/subscription")
    @Operation(
            operationId = "02_getMySubscription",
            summary = "내 구독 정보 조회",
            description = "현재 구독 중인 요금제 정보와 업그레이드 가능한 플랜 목록을 조회합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "구독 정보 조회 성공",
                    content = @Content(schema = @Schema(implementation = SubscriptionResponse.class))
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
    public ResponseEntity<ApiResponse<SubscriptionResponse>> getMySubscription(
            @AuthenticationPrincipal UserPrincipal userPrincipal
    ) {
        SubscriptionResponse response = subscriptionService.getSubscription(userPrincipal.getUserId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PatchMapping("/me/subscription/upgrade")
    @Operation(
            operationId = "03_upgradePlan",
            summary = "구독 플랜 업그레이드",
            description = "유저 플랜을 변경합니다. 상위 플랜은 즉시 반영되고, 하위 플랜(FREE 포함)은 만료일 기준 예약 변경(자동갱신 해지)으로 처리됩니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "업그레이드 성공",
                    content = @Content(schema = @Schema(implementation = SubscriptionResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청 (USER4001: 유효하지 않은 플랜 타입, USER4002: 동일한 플랜, USER4004: FREE 플랜 취소 시도, USER4005: 이미 예약된 변경 요청)",
                    content = @Content(schema = @Schema(example = "{\"isSuccess\":false,\"code\":\"USER4001\",\"message\":\"유효하지 않은 플랜 타입입니다.\"}"))
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
    public ResponseEntity<ApiResponse<SubscriptionResponse>> upgradePlan(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @Valid @RequestBody UpgradePlanRequest request
    ) {
        SubscriptionResponse response = subscriptionService.upgradePlan(userPrincipal.getUserId(), request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PatchMapping("/me/subscription/cancel")
    @Operation(
            operationId = "04_cancelSubscription",
            summary = "구독 플랜 취소",
            description = "유저 플랜을 STANDARD 또는 PRO에서 구독을 취소하고 기본 FREE 요금제를 적용합니다. 기존 결제에 대한 구독 만료날짜까지는 현재 플랜을 유지합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "구독 취소 성공",
                    content = @Content(schema = @Schema(
                            implementation = SubscriptionResponse.class,
                            example = "{\"currentPlan\":{\"name\":\"pro\",\"displayName\":\"Pro\",\"price\":14900,\"currency\":\"KRW\",\"billingCycle\":\"MONTHLY\"},\"cancelInfo\":{\"canceledAt\":\"2026-02-11T10:30:00\",\"effectiveUntil\":\"2026-03-10\",\"nextPlan\":\"free\"},\"billing\":{\"nextBillingDate\":null,\"autoRenew\":false}}"
                    ))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청 (USER4004: FREE 플랜 취소 시도, USER4005: 이미 동일한 플랜 변경 예약)",
                    content = @Content(schema = @Schema(example = "{\"isSuccess\":false,\"code\":\"USER4004\",\"message\":\"FREE 플랜은 취소할 수 없습니다.\"}"))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 실패",
                    content = @Content(schema = @Schema(example = "{\"isSuccess\":false,\"code\":\"AUTH4013\",\"message\":\"유효하지 않은 토큰입니다.\"}"))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "사용자 또는 활성 구독 없음 (USER4041: 사용자 없음, USER4042: 활성 구독 없음)",
                    content = @Content(schema = @Schema(example = "{\"isSuccess\":false,\"code\":\"USER4042\",\"message\":\"활성화된 구독을 찾을 수 없습니다.\"}"))
            )
    })
    public ResponseEntity<ApiResponse<SubscriptionResponse>> cancelSubscription(
            @AuthenticationPrincipal UserPrincipal userPrincipal
    ) {
        SubscriptionResponse response = subscriptionService.cancelSubscription(userPrincipal.getUserId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @DeleteMapping("/me")
    @Operation(
            operationId = "05_deleteUser",
            summary = "회원 탈퇴",
            description = "현재 로그인한 사용자의 계정을 영구 삭제합니다. 모든 노트, 파일, 구독 정보가 삭제됩니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "탈퇴 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "활성 구독 존재 (USER4006)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패 (AUTH4013)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자 없음 (USER4041)")
    })
    public ResponseEntity<ApiResponse<DeleteUserResponse>> deleteUser(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            HttpServletRequest request
    ) {
        Long userId = userPrincipal.getUserId();
        String accessToken = extractAccessToken(request);
        DeleteUserResponse response = userService.deleteUser(userId, accessToken);
        return ResponseEntity.ok(ApiResponse.success("회원 탈퇴가 완료되었습니다.", response));
    }

    private String extractAccessToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
