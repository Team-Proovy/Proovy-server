package com.proovy.domain.auth.controller;

import com.proovy.domain.auth.dto.request.KakaoLoginRequest;
import com.proovy.domain.auth.dto.request.TokenRefreshRequest;
import com.proovy.domain.auth.dto.response.LoginResponse;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "인증 관련 API")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login/kakao")
    @Operation(summary = "카카오 소셜 로그인",
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
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/refresh")
    @Operation(summary = "토큰 갱신",
            description = "Refresh Token으로 새로운 Access Token을 발급합니다.")
    public ResponseEntity<ApiResponse<TokenDto>> refreshToken(
            @Valid @RequestBody TokenRefreshRequest request
    ) {
        TokenDto tokens = authService.refreshToken(request.refreshToken());
        return ResponseEntity.ok(ApiResponse.success(tokens));
    }
}
