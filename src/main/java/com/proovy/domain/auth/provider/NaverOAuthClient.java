package com.proovy.domain.auth.provider;

import com.proovy.domain.auth.dto.naver.NaverTokenResponse;
import com.proovy.domain.auth.dto.naver.NaverUserResponse;
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
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class NaverOAuthClient {

    private final WebClient webClient;

    @Value("${oauth.naver.client-id}")
    private String clientId;

    @Value("${oauth.naver.client-secret}")
    private String clientSecret;

    @Value("${oauth.naver.redirect-uri}")
    private String redirectUri;

    @Value("${oauth.naver.authorize-uri}")
    private String authorizeUri;

    @Value("${oauth.naver.token-uri}")
    private String tokenUri;

    @Value("${oauth.naver.user-info-uri}")
    private String userInfoUri;

    /**
     * 네이버 로그인 URL 생성
     */
    public String generateAuthUrl(String state) {
        return authorizeUri +
                "?response_type=code" +
                "&client_id=" + clientId +
                "&redirect_uri=" + redirectUri +
                "&state=" + state;
    }

    /**
     * 인가 코드로 액세스 토큰 발급
     * redirectUri는 서버 설정값
     */
    public NaverTokenResponse getAccessToken(String code, String state) {
        try {
            NaverTokenResponse response = webClient.post()
                    .uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData("grant_type", "authorization_code")
                            .with("client_id", clientId)
                            .with("client_secret", clientSecret)
                            .with("redirect_uri", redirectUri)
                            .with("code", code)
                            .with("state", state))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, res ->
                            res.bodyToMono(String.class)
                                    .flatMap(body -> {
                                        log.error("네이버 토큰 발급 실패 (4xx): {}", body);
                                        return Mono.error(new BusinessException(ErrorCode.AUTH4011));
                                    }))
                    .onStatus(HttpStatusCode::is5xxServerError, res ->
                            Mono.error(new BusinessException(ErrorCode.AUTH5022)))
                    .bodyToMono(NaverTokenResponse.class)
                    .block();

            // 네이버는 200 OK로 에러 반환
            if (response != null && response.error() != null) {
                log.error("네이버 토큰 발급 에러: {} - {}", response.error(), response.errorDescription());
                throw new BusinessException(ErrorCode.AUTH4011);
            }

            return response;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("네이버 토큰 발급 API 호출 실패", e);
            throw new BusinessException(ErrorCode.AUTH5022);
        }
    }

    /**
     * 액세스 토큰으로 사용자 정보 조회
     */
    public NaverUserResponse getUserInfo(String accessToken) {
        try {
            NaverUserResponse response = webClient.get()
                    .uri(userInfoUri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, res ->
                            res.bodyToMono(String.class)
                                    .flatMap(body -> {
                                        log.error("네이버 사용자 정보 조회 실패 (4xx): {}", body);
                                        return Mono.error(new BusinessException(ErrorCode.AUTH4013));
                                    }))
                    .onStatus(HttpStatusCode::is5xxServerError, res ->
                            Mono.error(new BusinessException(ErrorCode.AUTH5022)))
                    .bodyToMono(NaverUserResponse.class)
                    .block();

            // 결과 코드 검증
            if (response == null || !"00".equals(response.resultcode())) {
                log.error("네이버 사용자 정보 조회 실패: {}", response != null ? response.message() : "null");
                throw new BusinessException(ErrorCode.AUTH4013);
            }

            return response;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("네이버 사용자 정보 조회 API 호출 실패", e);
            throw new BusinessException(ErrorCode.AUTH5022);
        }
    }
}
