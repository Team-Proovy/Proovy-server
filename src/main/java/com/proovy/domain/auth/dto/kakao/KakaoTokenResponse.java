package com.proovy.domain.auth.dto.kakao;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 카카오 토큰 발급 응답
 * POST https://kauth.kakao.com/oauth/token
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record KakaoTokenResponse(
        String tokenType,              // "bearer"
        String accessToken,            // 사용자 액세스 토큰
        Integer expiresIn,             // 액세스 토큰 만료 시간 (초)
        String refreshToken,           // 사용자 리프레시 토큰
        Integer refreshTokenExpiresIn, // 리프레시 토큰 만료 시간 (초)
        String scope                   // 인증된 사용자 정보 조회 권한 (선택)
) {}
