package com.proovy.domain.auth.dto.request;

public record LogoutRequest(
        String refreshToken
) {}
