package com.proovy.domain.auth.dto.response;

import com.proovy.domain.auth.dto.kakao.KakaoUserResponse;
import lombok.Builder;

/**
 * 프론트엔드 전달용 카카오 사용자 정보
 * 고유 ID, 이메일만 포함 (이름은 회원가입 시 직접 입력받음)
 */
@Builder
public record KakaoUserInfo(
        String id,      // 카카오 고유 회원번호 (signupToken에 포함되어 회원가입 시 providerUserId로 저장)
        String email    // 이메일
) {
    public static KakaoUserInfo from(KakaoUserResponse response) {
        var account = response.kakaoAccount();

        return KakaoUserInfo.builder()
                .id(String.valueOf(response.id()))
                .email(account != null ? account.email() : null)
                .build();
    }
}