package com.proovy.domain.auth.dto.response;

import lombok.Builder;

@Builder
public record LoginResponse(
        String loginType,          // "LOGIN" 또는 "SIGNUP_REQUIRED"
        UserDto user,              // 기존 유저인 경우 사용자 정보
        TokenDto token,            // 기존 유저인 경우 JWT 토큰
        String signupToken,        // 신규 유저인 경우 회원가입용 임시 토큰
        KakaoUserInfo kakaoInfo,   // 신규 유저인 경우 카카오에서 받은 정보
        NaverUserInfo naverInfo    // 네이버 신규 유저
) {
    public static LoginResponse login(UserDto user, TokenDto token) {
        return LoginResponse.builder()
                .loginType("LOGIN")
                .user(user)
                .token(token)
                .build();
    }

    // 카카오 신규 유저
    public static LoginResponse signupRequired(String signupToken, KakaoUserInfo kakaoInfo) {
        return LoginResponse.builder()
                .loginType("SIGNUP_REQUIRED")
                .signupToken(signupToken)
                .kakaoInfo(kakaoInfo)
                .build();
    }

    // 네이버 신규 유저
    public static LoginResponse signupRequired(String signupToken, NaverUserInfo naverInfo) {
        return LoginResponse.builder()
                .loginType("SIGNUP_REQUIRED")
                .signupToken(signupToken)
                .naverInfo(naverInfo)
                .build();
    }
}