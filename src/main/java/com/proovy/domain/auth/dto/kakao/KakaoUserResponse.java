package com.proovy.domain.auth.dto.kakao;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.LocalDateTime;

/**
 * 카카오 사용자 정보 응답
 * GET/POST https://kapi.kakao.com/v2/user/me
 *
 * 카카오에서 받아오는 정보: 고유 ID, 이메일
 * 이름은 비즈 앱 전환 + 사업자 인증 필요하여 사용 안 함
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record KakaoUserResponse(
        Long id,                       // 카카오 고유 회원번호 (providerUserId로 저장)
        LocalDateTime connectedAt,     // 서비스 연결 시각
        KakaoAccount kakaoAccount      // 카카오 계정 정보
) {

    /**
     * 카카오 계정 정보
     * 이메일만 사용 (이름은 회원가입 시 직접 입력받음)
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record KakaoAccount(
            Boolean hasEmail,           // 이메일 소유 여부
            Boolean isEmailValid,       // 이메일 유효 여부
            Boolean isEmailVerified,    // 이메일 인증 여부
            String email                // 카카오 계정 이메일
    ) {
    }
}