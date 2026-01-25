package com.proovy.domain.auth.dto.response;

import com.proovy.domain.auth.dto.naver.NaverUserResponse;
import lombok.Builder;

/**
 * 프론트엔드 전달용 네이버 사용자 정보
 * 신규 유저 회원가입 시 자동 채움에 사용
 *
 * - name: 개인정보 입력 화면에서 이름 자동 채움
 */
@Builder
public record NaverUserInfo(
        String id,      // 네이버 고유 ID
        String email,   // 이메일
        String name     // 이름 (회원가입 시 이름 필드 자동 채움)
) {
    public static NaverUserInfo from(NaverUserResponse response) {
        var user = response.response();
        if (user == null) {
            throw new IllegalArgumentException("Naver user response is null");
        }
        return NaverUserInfo.builder()
                .id(user.id())
                .email(user.email())
                .name(user.name())
                .build();
    }
}
