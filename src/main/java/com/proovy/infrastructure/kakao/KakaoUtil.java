package com.proovy.infrastructure.kakao;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KakaoDTO.OAuthToken requestToken(String accessCode) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded;charset=utf-8");

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "authorization_code");
        params.add("client_id", kakaoProperties.getClient());
        params.add("redirect_uri", kakaoProperties.getRedirect());
        params.add("code", accessCode);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    "https://kauth.kakao.com/oauth/token",
                    HttpMethod.POST,
                    request,
                    String.class
            );

            KakaoDTO.OAuthToken token = objectMapper.readValue(response.getBody(), KakaoDTO.OAuthToken.class);
            log.info("kakao access_token: {}", token.getAccess_token());
            return token;

        } catch (RestClientException e) {
            throw new AuthHandler(ErrorStatus._KAKAO_API_ERROR);
        } catch (JsonProcessingException e) {
            throw new AuthHandler(ErrorStatus._PARSING_ERROR);
        }
    }

    public KakaoDTO.KakaoProfile requestProfile(KakaoDTO.OAuthToken oAuthToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded;charset=utf-8");
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer " + oAuthToken.getAccess_token());

        HttpEntity<Void> request = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    "https://kapi.kakao.com/v2/user/me",
                    HttpMethod.GET,
                    request,
                    String.class
            );

            return objectMapper.readValue(response.getBody(), KakaoDTO.KakaoProfile.class);

        } catch (RestClientException e) {
            throw new AuthHandler(ErrorStatus._KAKAO_API_ERROR);
        } catch (JsonProcessingException e) {
            throw new AuthHandler(ErrorStatus._PARSING_ERROR);
        }
    }
}
