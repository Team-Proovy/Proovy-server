package com.proovy.domain.auth.dto.kakao;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.LocalDateTime;

/**
 * 카카오 사용자 정보 응답
 * GET/POST https://kapi.kakao.com/v2/user/me
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record KakaoUserResponse(
        Long id,                       // 회원번호
        LocalDateTime connectedAt,     // 서비스 연결 시각
        KakaoAccount kakaoAccount      // 카카오 계정 정보
) {

    /**
     * 카카오 계정 정보
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record KakaoAccount(
            String name,                // 카카오 계정 이름 (실명)
            Boolean hasEmail,           // 이메일 소유 여부
            Boolean isEmailValid,       // 이메일 유효 여부
            Boolean isEmailVerified,    // 이메일 인증 여부
            String email                // 카카오 계정 이메일
    ) {
    }
}