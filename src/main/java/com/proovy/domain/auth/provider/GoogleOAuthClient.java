package com.proovy.domain.auth.provider;

import com.proovy.domain.auth.dto.google.GoogleTokenResponse;
import com.proovy.domain.auth.dto.google.GoogleUserResponse;
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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleOAuthClient {

    private final WebClient webClient;

    @Value("${oauth.google.client-id}")
    private String clientId;

    @Value("${oauth.google.client-secret}")
    private String clientSecret;

    @Value("${oauth.google.redirect-uri}")
    private String redirectUri;

    @Value("${oauth.google.token-uri}")
    private String tokenUri;

    @Value("${oauth.google.user-info-uri}")
    private String userInfoUri;

    /**
     * 인가 코드로 액세스 토큰 발급
     * redirectUri는 서버 설정값 사용
     */
    public GoogleTokenResponse getAccessToken(String authorizationCode) {
        // 구글 인가 코드는 '/'를 포함하므로 URL 디코딩 필요 (4%2F... -> 4/...)
        String decodedCode = URLDecoder.decode(authorizationCode, StandardCharsets.UTF_8);

        try {
            return webClient.post()
                    .uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData("grant_type", "authorization_code")
                            .with("client_id", clientId)
                            .with("redirect_uri", redirectUri)
                            .with("code", decodedCode)
                            .with("client_secret", clientSecret))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, response ->
                            response.bodyToMono(String.class)
                                    .flatMap(body -> {
                                        log.error("구글 토큰 발급 실패 (4xx): {}", body);
                                        if (body.contains("redirect_uri_mismatch")) {
                                            return Mono.error(new BusinessException(ErrorCode.AUTH4001));
                                        }
                                        return Mono.error(new BusinessException(ErrorCode.AUTH4011));
                                    }))
                    .onStatus(HttpStatusCode::is5xxServerError, response ->
                            Mono.error(new BusinessException(ErrorCode.AUTH5023)))
                    .bodyToMono(GoogleTokenResponse.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("구글 토큰 발급 API 호출 실패", e);
            throw new BusinessException(ErrorCode.AUTH5023);
        }
    }

    /**
     * 액세스 토큰으로 사용자 정보 조회
     */
    public GoogleUserResponse getUserInfo(String accessToken) {
        try {
            return webClient.get()
                    .uri(userInfoUri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, response ->
                            response.bodyToMono(String.class)
                                    .flatMap(body -> {
                                        log.error("구글 사용자 정보 조회 실패 (4xx): {}", body);
                                        return Mono.error(new BusinessException(ErrorCode.AUTH4013));
                                    }))
                    .onStatus(HttpStatusCode::is5xxServerError, response ->
                            Mono.error(new BusinessException(ErrorCode.AUTH5023)))
                    .bodyToMono(GoogleUserResponse.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("구글 사용자 정보 조회 API 호출 실패", e);
            throw new BusinessException(ErrorCode.AUTH5023);
        }
    }
}
