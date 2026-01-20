package com.proovy.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

public record KakaoLoginRequest(
        @NotBlank(message = "인증 코드는 필수입니다")
        String authorizationCode,

        @NotBlank(message = "Redirect URI는 필수입니다")
        String redirectUri
) {}