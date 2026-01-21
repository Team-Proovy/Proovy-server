package com.proovy.domain.auth.dto.naver;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record NaverTokenResponse(
        String accessToken,      // 접근 토큰
        String refreshToken,     // 갱신 토큰
        String tokenType,        // 토큰 타입 (bearer)
        Integer expiresIn,       // 유효 기간 (초)
        String error,            // 에러 코드
        String errorDescription  // 에러 메시지
) {}
