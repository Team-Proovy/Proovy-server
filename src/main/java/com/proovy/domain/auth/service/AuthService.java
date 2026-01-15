package com.proovy.domain.auth.service;

import com.proovy.domain.auth.converter.AuthConverter;
import com.proovy.domain.user.entity.User;
import com.proovy.domain.user.repository.UserRepository;
import com.proovy.global.security.jwt.JwtUtil;
import com.proovy.infrastructure.kakao.KakaoDTO;
import com.proovy.infrastructure.kakao.KakaoUtil;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final KakaoUtil kakaoUtil;
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;

    @Transactional
    public AuthResult oAuthLogin(String accessCode) {
        KakaoDTO.OAuthToken token = kakaoUtil.requestToken(accessCode);
        KakaoDTO.KakaoProfile profile = kakaoUtil.requestProfile(token);

        String userKey = String.valueOf(profile.getId());

        boolean isNewUser = !userRepository.existsByUserKey(userKey);

        User user = userRepository.findByUserKey(userKey)
                .orElseGet(() -> createNewUser(userKey, profile));

        String accessToken = jwtUtil.createAccessToken(userKey, user.getRole().name());
        String refreshToken = jwtUtil.createRefreshToken(userKey);

        return new AuthResult(
                user,
                accessToken,
                refreshToken,
                jwtUtil.getAccessTokenExpiresIn(),
                jwtUtil.getRefreshTokenExpiresIn(),
                isNewUser ? "SIGNUP" : "LOGIN"
        );
    }

    private User createNewUser(String userKey, KakaoDTO.KakaoProfile profile) {
        User newUser = AuthConverter.toUser(userKey, profile);
        return userRepository.save(newUser);
    }

    @Getter
    @RequiredArgsConstructor
    public static class AuthResult {
        private final User user;
        private final String accessToken;
        private final String refreshToken;
        private final long accessTokenExpiresIn;
        private final long refreshTokenExpiresIn;
        private final String loginType;
    }
}
