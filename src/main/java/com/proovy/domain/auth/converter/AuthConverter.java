package com.proovy.domain.auth.converter;

import com.proovy.domain.user.entity.Role;
import com.proovy.domain.user.entity.User;
import com.proovy.infrastructure.kakao.KakaoDTO;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AuthConverter {

    private AuthConverter() {}

    public static User toUser(String userKey, KakaoDTO.KakaoProfile profile) {
        log.info("=== Converting Kakao Profile to User ===");
        log.info("User Key: {}", userKey);

        // 카카오 프로필에서 닉네임 추출 (없으면 기본값 사용)
        String nickname = "user_" + userKey; // 기본값
        String email = null;

        if (profile.getKakao_account() != null) {
            log.info("Kakao account found");

            if (profile.getKakao_account().getProfile() != null
                && profile.getKakao_account().getProfile().getNickname() != null) {
                nickname = profile.getKakao_account().getProfile().getNickname();
                log.info("Using Kakao nickname: {}", nickname);
            } else {
                log.warn("Kakao profile or nickname is null, using default: {}", nickname);
            }

            email = profile.getKakao_account().getEmail();
            log.info("Email: {}", email != null ? email : "null");
        } else {
            log.warn("Kakao account is null, using default nickname: {}", nickname);
        }

        log.info("Creating User with nickname: {}, email: {}", nickname, email);

        return User.builder()
                .userKey(userKey)
                .nickname(nickname)
                .role(Role.USER)
                .build();
    }
}
