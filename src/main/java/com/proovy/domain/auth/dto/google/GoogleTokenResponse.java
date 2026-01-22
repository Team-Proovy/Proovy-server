package com.proovy.domain.auth.dto.google;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 구글 토큰 발급 응답
 * POST https://oauth2.googleapis.com/token
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GoogleTokenResponse(
        String tokenType,      // "Bearer"
        String accessToken,    // 사용자 액세스 토큰
        Integer expiresIn,     // 액세스 토큰 만료 시간 (초)
        String refreshToken,   // 사용자 리프레시 토큰 (첫 인증 시에만 반환)
        String scope,          // 인증된 사용자 정보 조회 권한
        String idToken         // JWT 형식의 ID 토큰 (openid scope 사용 시)
) {}
