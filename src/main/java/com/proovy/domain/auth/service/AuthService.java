package com.proovy.domain.auth.service;

import com.proovy.domain.auth.converter.AuthConverter;
import com.proovy.domain.user.entity.User;
import com.proovy.domain.user.repository.UserRepository;
import com.proovy.global.security.jwt.JwtUtil;
import com.proovy.infrastructure.kakao.KakaoDTO;
import com.proovy.infrastructure.kakao.KakaoUtil;
import jakarta.servlet.http.HttpServletResponse;

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
    public User oAuthLogin(String accessCode, HttpServletResponse response) {
        KakaoDTO.OAuthToken token = kakaoUtil.requestToken(accessCode);
        KakaoDTO.KakaoProfile profile = kakaoUtil.requestProfile(token);

        String userKey = String.valueOf(profile.getId());

        User user = userRepository.findByUserKey(userKey)
                .orElseGet(() -> createNewUser(userKey));

        String jwt = jwtUtil.createAccessToken(userKey, user.getRole().name());
        response.setHeader("Authorization", "Bearer " + jwt);

        return user;
    }

    private User createNewUser(String userKey) {
        User newUser = AuthConverter.toUser(userKey);
        return userRepository.save(newUser);
    }
}
