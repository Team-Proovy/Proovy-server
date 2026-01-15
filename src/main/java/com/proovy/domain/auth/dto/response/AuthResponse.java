package com.proovy.domain.auth.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AuthResponse {

    @JsonProperty("loginType")
    private String loginType;  // "LOGIN" or "SIGNUP"

    @JsonProperty("user")
    private UserInfo user;

    @JsonProperty("token")
    private TokenInfo token;

    @Getter
    @Builder
    public static class UserInfo {
        @JsonProperty("userId")
        private Long userId;

        @JsonProperty("nickname")
        private String nickname;

        @JsonProperty("phoneVerified")
        private Boolean phoneVerified;
    }

    @Getter
    @Builder
    public static class TokenInfo {
        @JsonProperty("grantType")
        private String grantType;

        @JsonProperty("accessToken")
        private String accessToken;

        @JsonProperty("accessTokenExpiresIn")
        private Long accessTokenExpiresIn;

        @JsonProperty("refreshToken")
        private String refreshToken;

        @JsonProperty("refreshTokenExpiresIn")
        private Long refreshTokenExpiresIn;
    }
}

