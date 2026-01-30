package com.proovy.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 구글 로그인 요청
 * redirectUri는 서버 설정값 사용
 */
public record GoogleLoginRequest(
        @NotBlank(message = "인증 코드는 필수입니다")
        String authorizationCode
) {}
