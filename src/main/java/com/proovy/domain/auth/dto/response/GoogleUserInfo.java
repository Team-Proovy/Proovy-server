package com.proovy.domain.auth.dto.response;

import com.proovy.domain.auth.dto.google.GoogleUserResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/**
 * 프론트엔드 전달용 구글 사용자 정보
 * 고유 ID, 이메일만 포함 (이름은 회원가입 시 직접 입력받음)
 */
@Builder
@Schema(description = "구글 사용자 정보")
public record GoogleUserInfo(
        String id,      // 구글 고유 회원번호 (signupToken에 포함되어 회원가입 시 providerUserId로 저장)
        String email,   // 이메일
        String name     // 사용자 이름 (null 가능)
) {
    public static GoogleUserInfo from(GoogleUserResponse response) {
        if (response == null) {
            throw new IllegalArgumentException("Google user response is null");
        }
        return GoogleUserInfo.builder()
                .id(response.id())
                .email(response.email())
                .name(response.name())
                .build();
    }
}