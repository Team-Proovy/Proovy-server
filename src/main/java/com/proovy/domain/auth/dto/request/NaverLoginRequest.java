package com.proovy.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 네이버 로그인 요청
 * redirectUri는 서버 설정값 사용
 */
public record NaverLoginRequest(
        @NotBlank(message = "인증 코드는 필수입니다")
        String code,

        @NotBlank(message = "state는 필수입니다")
        String state
) {}
