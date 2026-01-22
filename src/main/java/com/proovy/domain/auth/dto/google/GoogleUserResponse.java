package com.proovy.domain.auth.dto.google;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 구글 사용자 정보 응답
 * GET https://www.googleapis.com/oauth2/v2/userinfo
 *
 * 구글에서 받아오는 정보: 고유 ID, 이메일, 이름
 * 휴대폰 번호는 제공하지 않음 (회원가입 시 직접 입력)
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GoogleUserResponse(
        String id,              // 구글 고유 사용자 ID (providerUserId)
        String email,           // 이메일
        Boolean verifiedEmail,  // 이메일 인증 여부
        String name,            // 전체 이름 (null 가능)
        String givenName,       // 이름 (선택)
        String familyName,      // 성 (선택)
        String picture,         // 프로필 이미지 URL (고정 로고)
        String locale           // 언어 설정 (ko, en 등)
) {}
