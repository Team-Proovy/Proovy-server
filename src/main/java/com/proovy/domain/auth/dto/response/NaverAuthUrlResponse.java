package com.proovy.domain.auth.dto.response;

import lombok.Builder;

/**
 * 네이버 로그인 URL 응답
 * 프론트엔드는 이 URL로 리다이렉트
 */
@Builder
public record NaverAuthUrlResponse(
        String authUrl,    // 네이버 로그인 URL (state 포함)
        String state       // 프론트에서 콜백 시 함께 전달할 state
) {}
