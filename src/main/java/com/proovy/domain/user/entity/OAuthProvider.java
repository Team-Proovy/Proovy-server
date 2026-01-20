package com.proovy.domain.user.entity;

public enum OAuthProvider {
    KAKAO("/images/logo/kakao.png"),
    NAVER("/images/logo/naver.png"),
    GOOGLE("/images/logo/google.png");

    private final String logoUrl;

    OAuthProvider(String logoUrl) {
        this.logoUrl = logoUrl;
    }

    public String getLogoUrl() {
        return logoUrl;
    }
}
