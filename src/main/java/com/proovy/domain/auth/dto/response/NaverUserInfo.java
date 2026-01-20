package com.proovy.domain.auth.dto.response;

import com.proovy.domain.auth.dto.naver.NaverUserResponse;
import lombok.Builder;

/**
 * 프론트엔드 전달용 네이버 사용자 정보
 * 신규 유저 회원가입 시 자동 채움에 사용
 *
 * - name: 개인정보 입력 화면에서 이름 자동 채움
 * - mobile: 휴대폰 인증 화면에서 번호 자동 채움
 */
@Builder
public record NaverUserInfo(
        String id,      // 네이버 고유 ID
        String email,   // 이메일
        String name,    // 이름 (회원가입 시 이름 필드 자동 채움)
        String mobile   // 휴대폰 번호 (인증 화면에서 자동 채움)
) {
    public static NaverUserInfo from(NaverUserResponse response) {
        var user = response.response();
        return NaverUserInfo.builder()
                .id(user.id())
                .email(user.email())
                .name(user.name())
                .mobile(normalizeMobile(user.mobile()))
                .build();
    }

    /**
     * 번호 형식 정규화 (010-1234-5678 -> 01012345678)
     * 카카오와 동일한 형식으로 맞추기 위해 하이픈 및 특수문자 제거
     */
    private static String normalizeMobile(String mobile) {
        if (mobile == null) return null;
        return mobile.replaceAll("[^0-9]", "");
    }
}
