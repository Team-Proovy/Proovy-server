package com.proovy.infrastructure.jwt;

public class TokenKey {
    public static final String TOKEN_PREFIX = "Bearer ";
    public static final String ACCESS_TOKEN = "accessToken";
    public static final String REFRESH_TOKEN = "refreshToken";

    private TokenKey() {
        // Utility class
    }
}

