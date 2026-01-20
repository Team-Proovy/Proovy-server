package com.proovy.domain.auth.dto.response;

import lombok.Builder;

@Builder
public record TokenDto(
        String accessToken,
        String refreshToken,
        Long accessTokenExpiresIn,     // 초 단위
        Long refreshTokenExpiresIn     // 초 단위
) {}