package com.proovy.domain.auth.controller;

import com.proovy.domain.auth.dto.response.AuthResponse;
import com.proovy.domain.auth.service.AuthService;
import com.proovy.global.response.ApiResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("")
public class AuthController {

    private final AuthService authService;

    @GetMapping("/auth/login/kakao")
    public ApiResponse<AuthResponse> kakaoLogin(@RequestParam("code") String accessCode) {
        AuthService.AuthResult result = authService.oAuthLogin(accessCode);

        // 응답 DTO 생성
        AuthResponse response = AuthResponse.builder()
                .loginType(result.getLoginType())
                .user(AuthResponse.UserInfo.builder()
                        .userId(result.getUser().getUserId())
                        .nickname(result.getUser().getNickname())
                        .phoneVerified(result.getUser().getPhone() != null && !result.getUser().getPhone().isEmpty())
                        .build())
                .token(AuthResponse.TokenInfo.builder()
                        .grantType("Bearer")
                        .accessToken(result.getAccessToken())
                        .accessTokenExpiresIn(result.getAccessTokenExpiresIn())
                        .refreshToken(result.getRefreshToken())
                        .refreshTokenExpiresIn(result.getRefreshTokenExpiresIn())
                        .build())
                .build();

        return ApiResponse.success(response);
    }
}
