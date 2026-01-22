package com.proovy.domain.auth.dto.response;

import com.proovy.domain.auth.dto.google.GoogleUserResponse;
import lombok.Builder;

/**
 * 프론트엔드 전달용 구글 사용자 정보
 * 고유 ID, 이메일, 이름 포함 (휴대폰 번호는 회원가입 시 직접 입력받음)
 */
@Builder
public record GoogleUserInfo(
        String id,      // 구글 고유 사용자 ID (signupToken에 포함되어 회원가입 시 providerUserId로 저장)
        String email,   // 이메일
        String name     // 이름 (null 가능)
) {
    public static GoogleUserInfo from(GoogleUserResponse response) {
        return GoogleUserInfo.builder()
                .id(response.id())
                .email(response.email())
                .name(response.name())
                .build();
    }
}
