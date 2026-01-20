package com.proovy.domain.auth.service;

import com.proovy.domain.auth.dto.kakao.KakaoTokenResponse;
import com.proovy.domain.auth.dto.kakao.KakaoUserResponse;
import com.proovy.domain.auth.dto.naver.NaverTokenResponse;
import com.proovy.domain.auth.dto.naver.NaverUserResponse;
import com.proovy.domain.auth.dto.request.KakaoLoginRequest;
import com.proovy.domain.auth.dto.request.NaverLoginRequest;
import com.proovy.domain.auth.dto.response.*;
import com.proovy.domain.auth.entity.NaverState;
import com.proovy.domain.auth.entity.RefreshToken;
import com.proovy.domain.auth.provider.KakaoOAuthClient;
import com.proovy.domain.auth.provider.NaverOAuthClient;
import com.proovy.domain.auth.repository.NaverStateRepository;
import com.proovy.domain.auth.repository.RefreshTokenRepository;
import com.proovy.domain.user.entity.OAuthProvider;
import com.proovy.domain.user.entity.User;
import com.proovy.domain.user.repository.UserRepository;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.response.ErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final KakaoOAuthClient kakaoClient;
    private final NaverOAuthClient naverClient;
    private final NaverStateRepository naverStateRepository;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${oauth.naver.state-ttl:300}")
    private Long stateTtl;

    /**
     * 네이버 로그인 URL 생성
     * state를 생성하고 Redis에 저장 후 authorize URL 반환
     */
    public NaverAuthUrlResponse generateNaverAuthUrl() {
        // 1. state 생성 (UUID)
        String state = UUID.randomUUID().toString();

        // 2. Redis에 state 저장 (TTL 적용)
        NaverState naverState = NaverState.builder()
                .state(state)
                .ttl(stateTtl)
                .build();
        naverStateRepository.save(naverState);

        // 3. 네이버 로그인 URL 생성
        String authUrl = naverClient.generateAuthUrl(state);

        log.info("네이버 로그인 URL 생성, state: {}", state);
        return NaverAuthUrlResponse.builder()
                .authUrl(authUrl)
                .state(state)
                .build();
    }

    /**
     * 카카오 로그인 처리
     */
    @Transactional
    public LoginResponse kakaoLogin(KakaoLoginRequest request) {
        // 1. 카카오 액세스 토큰 발급
        KakaoTokenResponse kakaoToken = kakaoClient.getAccessToken(
                request.authorizationCode(),
                request.redirectUri()
        );
        log.info("카카오 토큰 발급 성공, expires_in: {}", kakaoToken.expiresIn());

        // 2. 카카오 사용자 정보 조회
        KakaoUserResponse kakaoUser = kakaoClient.getUserInfo(kakaoToken.accessToken());
        log.info("카카오 사용자 정보 조회 성공, id: {}", kakaoUser.id());

        // 3. 기존 유저 조회 (OAuthProvider enum 사용)
        String providerUserId = String.valueOf(kakaoUser.id());
        Optional<User> existingUser = userRepository
                .findByProviderAndProviderUserId(OAuthProvider.KAKAO, providerUserId);

        // 4. 분기 처리
        if (existingUser.isPresent()) {
            // 기존 유저: JWT 발급
            User user = existingUser.get();
            TokenDto tokens = jwtTokenProvider.generateTokens(user.getId());

            // Refresh Token Redis 저장
            saveRefreshToken(user.getId(), tokens.refreshToken());

            log.info("기존 유저 로그인 성공, userId: {}", user.getId());
            return LoginResponse.login(UserDto.from(user), tokens);

        } else {
            // 신규 유저: signupToken 발급
            KakaoUserInfo kakaoInfo = KakaoUserInfo.from(kakaoUser);
            String signupToken = jwtTokenProvider.generateSignupToken(kakaoInfo);

            log.info("신규 유저 감지, 회원가입 필요, kakaoId: {}", kakaoUser.id());
            return LoginResponse.signupRequired(signupToken, kakaoInfo);
        }
    }

    /**
     * 네이버 로그인 처리
     */
    @Transactional
    public LoginResponse naverLogin(@Valid NaverLoginRequest request) {
        // 1. state 검증 (Redis에서 확인 후 삭제)
        naverStateRepository.findById(request.state())
                .orElseThrow(() -> {
                    log.warn("네이버 state 검증 실패: {}", request.state());
                    return new BusinessException(ErrorCode.AUTH4002);
                });
        naverStateRepository.deleteById(request.state());

        // 2. 네이버 액세스 토큰 발급
        NaverTokenResponse naverToken = naverClient.getAccessToken(
                request.code(),
                request.state()
        );
        log.info("네이버 토큰 발급 성공, expires_in: {}", naverToken.expiresIn());

        // 3. 네이버 사용자 정보 조회
        NaverUserResponse naverUser = naverClient.getUserInfo(naverToken.accessToken());
        log.info("네이버 사용자 정보 조회 성공, id: {}", naverUser.response().id());

        // 4. 기존 유저 조회
        String providerUserId = naverUser.response().id();
        Optional<User> existingUser = userRepository
                .findByProviderAndProviderUserId(OAuthProvider.NAVER, providerUserId);

        // 5. 분기 처리
        if (existingUser.isPresent()) {
            // 기존 유저: JWT 발급
            User user = existingUser.get();
            TokenDto tokens = jwtTokenProvider.generateTokens(user.getId());
            saveRefreshToken(user.getId(), tokens.refreshToken());

            log.info("기존 유저 로그인 성공 (네이버), userId: {}", user.getId());
            return LoginResponse.login(UserDto.from(user), tokens);
        } else {
            // 신규 유저: signupToken 발급
            NaverUserInfo naverInfo = NaverUserInfo.from(naverUser);
            String signupToken = jwtTokenProvider.generateSignupToken(naverInfo);

            log.info("신규 유저 감지 (네이버), 회원가입 필요, naverId: {}", providerUserId);
            return LoginResponse.signupRequired(signupToken, naverInfo);
        }
    }

    /**
     * JWT 토큰 갱신
     */
    @Transactional
    public TokenDto refreshToken(String refreshToken) {
        // 1. 토큰 유효성 검증 (refresh 타입만 허용)
        jwtTokenProvider.validateRefreshToken(refreshToken);

        // 2. Redis에서 저장된 토큰 확인
        RefreshToken savedToken = refreshTokenRepository.findById(refreshToken)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH4013));

        // 3. 새 토큰 발급
        TokenDto newTokens = jwtTokenProvider.generateTokens(savedToken.getUserId());

        // 4. 기존 토큰 삭제 후 새 토큰 저장
        refreshTokenRepository.delete(savedToken);
        saveRefreshToken(savedToken.getUserId(), newTokens.refreshToken());

        return newTokens;
    }

    private void saveRefreshToken(Long userId, String refreshToken) {
        RefreshToken token = RefreshToken.builder()
                .token(refreshToken)
                .userId(userId)
                .createdAt(LocalDateTime.now())
                .build();
        refreshTokenRepository.save(token);
    }
}
