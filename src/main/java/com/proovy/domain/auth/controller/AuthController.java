package com.proovy.domain.auth.controller;

import com.proovy.domain.auth.dto.request.GoogleLoginRequest;
import com.proovy.domain.auth.dto.request.KakaoLoginRequest;
import com.proovy.domain.auth.dto.request.NaverLoginRequest;
import com.proovy.domain.auth.dto.request.TokenRefreshRequest;
import com.proovy.domain.auth.dto.response.LoginResponse;
import com.proovy.domain.auth.dto.response.NaverAuthUrlResponse;
import com.proovy.domain.auth.dto.response.TokenDto;
import com.proovy.domain.auth.service.AuthService;
import com.proovy.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "인증 관련 API")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login/kakao")
    @Operation(
            operationId = "01_kakaoLogin",
            summary = "카카오 소셜 로그인",
            description = "카카오 인가 코드로 로그인합니다. 신규 유저는 회원가입 토큰을 반환합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그인 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 요청 (AUTH4001)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패 (AUTH4011)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 오류 (AUTH5021)")
    })
    public ResponseEntity<ApiResponse<LoginResponse>> kakaoLogin(
            @Valid @RequestBody KakaoLoginRequest request
    ) {
        log.info("카카오 로그인 요청, redirectUri: {}", request.redirectUri());
        LoginResponse response = authService.kakaoLogin(request);

        String message = "SIGNUP_REQUIRED".equals(response.loginType())
                ? "휴대폰 인증이 필요합니다."
                : "로그인에 성공했습니다.";

        return ResponseEntity.ok(ApiResponse.success(message, response));
    }

    @GetMapping("/naver/url")
    @Operation(
            operationId = "02_getNaverAuthUrl",
            summary = "네이버 로그인 URL 생성",
            description = "네이버 OAuth 인증 URL을 생성합니다. state가 포함되어 있으며, 프론트엔드는 이 URL로 리다이렉트합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "URL 생성 성공")
    })
    public ResponseEntity<ApiResponse<NaverAuthUrlResponse>> getNaverAuthUrl() {
        NaverAuthUrlResponse response = authService.generateNaverAuthUrl();
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/login/naver")
    @Operation(
            operationId = "03_naverLogin",
            summary = "네이버 소셜 로그인",
            description = "네이버 인가 코드와 state로 로그인합니다. 신규 유저는 회원가입 토큰을 반환합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그인 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패 (AUTH4002, AUTH4011)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 오류 (AUTH5022)")
    })
    public ResponseEntity<ApiResponse<LoginResponse>> naverLogin(
            @Valid @RequestBody NaverLoginRequest request
    ) {
        log.info("네이버 로그인 요청");
        LoginResponse response = authService.naverLogin(request);

        String message = "SIGNUP_REQUIRED".equals(response.loginType())
                ? "휴대폰 인증이 필요합니다."
                : "로그인에 성공했습니다.";

        return ResponseEntity.ok(ApiResponse.success(message, response));
    }

    @PostMapping("/login/google")
    @Operation(
            operationId = "05_googleLogin",
            summary = "구글 소셜 로그인",
            description = "구글 인가 코드로 로그인합니다. 신규 유저는 회원가입 토큰을 반환합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그인 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 요청 (AUTH4001)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패 (AUTH4011)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "서버 오류 (AUTH5023)")
    })
    public ResponseEntity<ApiResponse<LoginResponse>> googleLogin(
            @Valid @RequestBody GoogleLoginRequest request
    ) {
        log.info("구글 로그인 요청, redirectUri: {}", request.redirectUri());
        LoginResponse response = authService.googleLogin(request);

        String message = "SIGNUP_REQUIRED".equals(response.loginType())
                ? "휴대폰 인증이 필요합니다."
                : "로그인에 성공했습니다.";

        return ResponseEntity.ok(ApiResponse.success(message, response));
    }

    @PostMapping("/refresh")
    @Operation(
            operationId = "04_refreshToken",
            summary = "토큰 갱신",
            description = "Refresh Token으로 새로운 Access Token을 발급합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "토큰 갱신 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패 (AUTH4012, AUTH4013)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "유효성 실패 (COMMON400)")
    })
    public ResponseEntity<ApiResponse<TokenDto>> refreshToken(
            @Valid @RequestBody TokenRefreshRequest request
    ) {
        TokenDto tokens = authService.refreshToken(request.refreshToken());
        return ResponseEntity.ok(ApiResponse.success("토큰이 갱신되었습니다.", tokens));
    }
}
