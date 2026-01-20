package com.proovy.domain.auth.provider;

import com.proovy.domain.auth.dto.kakao.KakaoTokenResponse;
import com.proovy.domain.auth.dto.kakao.KakaoUserResponse;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoOAuthClient {

    private final WebClient webClient;

    @Value("${oauth.kakao.client-id}")
    private String clientId;

    @Value("${oauth.kakao.client-secret}")
    private String clientSecret;

    @Value("${oauth.kakao.token-uri}")
    private String tokenUri;

    @Value("${oauth.kakao.user-info-uri}")
    private String userInfoUri;

    /**
     * 인가 코드로 액세스 토큰 발급
     */
    public KakaoTokenResponse getAccessToken(String authorizationCode, String redirectUri) {
        try {
            return webClient.post()
                    .uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData("grant_type", "authorization_code")
                            .with("client_id", clientId)
                            .with("redirect_uri", redirectUri)
                            .with("code", authorizationCode)
                            .with("client_secret", clientSecret))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, response ->
                            response.bodyToMono(String.class)
                                    .flatMap(body -> {
                                        log.error("카카오 토큰 발급 실패 (4xx): {}", body);
                                        if (body.contains("KOE010")) {
                                            return Mono.error(new BusinessException(ErrorCode.AUTH4001));
                                        }
                                        return Mono.error(new BusinessException(ErrorCode.AUTH4011));
                                    }))
                    .onStatus(HttpStatusCode::is5xxServerError, response ->
                            Mono.error(new BusinessException(ErrorCode.AUTH5021)))
                    .bodyToMono(KakaoTokenResponse.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("카카오 토큰 발급 API 호출 실패", e);
            throw new BusinessException(ErrorCode.AUTH5021);
        }
    }

    /**
     * 액세스 토큰으로 사용자 정보 조회
     */
    public KakaoUserResponse getUserInfo(String accessToken) {
        try {
            return webClient.get()
                    .uri(userInfoUri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, response ->
                            response.bodyToMono(String.class)
                                    .flatMap(body -> {
                                        log.error("카카오 사용자 정보 조회 실패 (4xx): {}", body);
                                        return Mono.error(new BusinessException(ErrorCode.AUTH4013));
                                    }))
                    .onStatus(HttpStatusCode::is5xxServerError, response ->
                            Mono.error(new BusinessException(ErrorCode.AUTH5021)))
                    .bodyToMono(KakaoUserResponse.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("카카오 사용자 정보 조회 API 호출 실패", e);
            throw new BusinessException(ErrorCode.AUTH5021);
        }
    }
}
