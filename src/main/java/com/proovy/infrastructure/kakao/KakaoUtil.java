package com.proovy.infrastructure.kakao;

import com.proovy.global.error.AuthHandler;
import com.proovy.global.error.ErrorStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoUtil {

    private final KakaoProperties kakaoProperties;
    private final RestTemplate restTemplate;

    public KakaoDTO.OAuthToken requestToken(String accessCode) {
        log.info("=== Kakao Token Request Start ===");
        log.info("Client ID: {}", kakaoProperties.getClientId());
        log.info("Redirect URI: {}", kakaoProperties.getRedirectUri());
        log.info("Access Code: {}", accessCode);

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded;charset=utf-8");

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "authorization_code");
        params.add("client_id", kakaoProperties.getClientId());
        params.add("redirect_uri", kakaoProperties.getRedirectUri());
        params.add("code", accessCode);

        // client_secret이 있으면 추가 (카카오는 선택사항이지만 설정했다면 필수)
        if (kakaoProperties.getClientSecret() != null && !kakaoProperties.getClientSecret().isEmpty()) {
            params.add("client_secret", kakaoProperties.getClientSecret());
            log.info("Client Secret added to request");
        }

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);

        try {
            log.info("Sending request to Kakao token endpoint...");
            ResponseEntity<KakaoDTO.OAuthToken> response = restTemplate.exchange(
                    "https://kauth.kakao.com/oauth/token",
                    HttpMethod.POST,
                    request,
                    KakaoDTO.OAuthToken.class
            );

            log.info("Kakao token response status: {}", response.getStatusCode());
            KakaoDTO.OAuthToken token = response.getBody();

            if (token == null) {
                log.error("Kakao token response body is null");
                throw new AuthHandler(ErrorStatus._KAKAO_API_ERROR);
            }

            log.info("kakao access_token: {}", token.getAccess_token());
            log.info("=== Kakao Token Request Success ===");
            return token;

        } catch (RestClientException e) {
            log.error("=== Kakao API Error ===");
            log.error("Error message: {}", e.getMessage());
            log.error("Error class: {}", e.getClass().getName());
            if (e.getCause() != null) {
                log.error("Cause: {}", e.getCause().getMessage());
            }
            throw new AuthHandler(ErrorStatus._KAKAO_API_ERROR);
        }
    }

    public KakaoDTO.KakaoProfile requestProfile(KakaoDTO.OAuthToken oAuthToken) {
        log.info("=== Kakao Profile Request Start ===");
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded;charset=utf-8");
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer " + oAuthToken.getAccess_token());

        HttpEntity<Void> request = new HttpEntity<>(headers);

        try {
            log.info("Sending request to Kakao profile endpoint...");
            ResponseEntity<KakaoDTO.KakaoProfile> response = restTemplate.exchange(
                    "https://kapi.kakao.com/v2/user/me",
                    HttpMethod.GET,
                    request,
                    KakaoDTO.KakaoProfile.class
            );

            log.info("Kakao profile response status: {}", response.getStatusCode());
            KakaoDTO.KakaoProfile profile = response.getBody();

            if (profile == null) {
                log.error("Kakao profile response body is null");
                throw new AuthHandler(ErrorStatus._KAKAO_API_ERROR);
            }

            // 프로필 상세 정보 로깅
            log.info("=== Kakao Profile Details ===");
            log.info("User ID: {}", profile.getId());
            log.info("Connected At: {}", profile.getConnected_at());

            if (profile.getKakao_account() != null) {
                log.info("Kakao Account exists: YES");
                log.info("Email: {}", profile.getKakao_account().getEmail());

                if (profile.getKakao_account().getProfile() != null) {
                    log.info("Profile exists: YES");
                    log.info("Nickname: {}", profile.getKakao_account().getProfile().getNickname());
                } else {
                    log.warn("Profile is NULL - nickname not available");
                }
            } else {
                log.warn("Kakao Account is NULL - no user info available");
            }

            log.info("=== Kakao Profile Request Success ===");
            return profile;

        } catch (RestClientException e) {
            log.error("=== Kakao Profile API Error ===");
            log.error("Error message: {}", e.getMessage());
            log.error("Error class: {}", e.getClass().getName());
            if (e.getCause() != null) {
                log.error("Cause: {}", e.getCause().getMessage());
            }
            throw new AuthHandler(ErrorStatus._KAKAO_API_ERROR);
        }
    }
}
