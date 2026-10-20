package com.proovy.global.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code proovy.cors.*} — 브라우저 CORS 허용 origin.
 *
 * @param allowedOriginPatterns 허용 origin pattern({@link org.springframework.web.cors.CorsConfiguration#setAllowedOriginPatterns}
 *                              문법, 예 {@code http://localhost:[*]}). 기본·운영은 {@code https://proovy.ai.kr}만이고,
 *                              local profile만 개발 Front(Vite dev server) origin을 더한다. credentials를 허용하므로
 *                              모든 origin({@code *})은 받지 않는다
 */
@ConfigurationProperties(prefix = "proovy.cors")
public record CorsProperties(@DefaultValue("https://proovy.ai.kr") List<String> allowedOriginPatterns) {

    public CorsProperties {
        if (allowedOriginPatterns == null || allowedOriginPatterns.isEmpty()) {
            throw new IllegalArgumentException("proovy.cors.allowed-origin-patterns must not be empty");
        }
        for (String pattern : allowedOriginPatterns) {
            if (pattern == null || pattern.isBlank() || pattern.trim().equals("*")) {
                throw new IllegalArgumentException("proovy.cors.allowed-origin-patterns must list explicit origins");
            }
        }
        allowedOriginPatterns = List.copyOf(allowedOriginPatterns);
    }
}
