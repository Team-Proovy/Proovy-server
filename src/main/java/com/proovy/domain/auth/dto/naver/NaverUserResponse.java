package com.proovy.domain.auth.dto.naver;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 네이버 사용자 정보 응답
 * GET https://openapi.naver.com/v1/nid/me
 *
 * 사용하는 필드: id, email, name
 * 프로필 이미지는 네이버 기본 로고로 고정
 */
public record NaverUserResponse(
        String resultcode,       // 결과 코드 ("00": 성공)
        String message,          // 결과 메시지
        Response response        // 사용자 정보
) {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Response(
            String id,           // 네이버 고유 ID (providerUserId로 저장)
            String email,        // 이메일
            String name,         // 이름
            String mobile        // 휴대폰 번호 (010-1234-5678 형식)
    ) {}
}
