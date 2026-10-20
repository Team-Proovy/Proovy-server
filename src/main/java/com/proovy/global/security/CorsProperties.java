package com.proovy.global.security;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code proovy.cors.*} — 브라우저 CORS 허용 origin.
 *
 * @param allowedOriginPatterns 허용 origin pattern({@link org.springframework.web.cors.CorsConfiguration#setAllowedOriginPatterns}
 *                              문법, 예 {@code http://localhost:[*]}). 기본·운영은 {@code https://proovy.ai.kr}만이고,
 *                              local profile만 개발 Front(Vite dev server) origin을 더한다. credentials를 허용하므로
 *                              항목(Spring이 쉼표로 나누는 조각 포함)마다 {@code scheme://host[:port|:[*]]}만 받는다 —
 *                              wildcard는 포트({@code :[*]})에만 쓸 수 있고 {@code *}·{@code https://*} 같은 host wildcard는 거부한다
 */
@ConfigurationProperties(prefix = "proovy.cors")
public record CorsProperties(@DefaultValue("https://proovy.ai.kr") List<String> allowedOriginPatterns) {

    private static final Pattern EXPLICIT_ORIGIN =
            Pattern.compile("https?://[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:([0-9]{1,5}|\\[\\*]))?");

    public CorsProperties {
        if (allowedOriginPatterns == null || allowedOriginPatterns.isEmpty()) {
            throw new IllegalArgumentException("proovy.cors.allowed-origin-patterns must not be empty");
        }
        for (String pattern : allowedOriginPatterns) {
            // CorsConfiguration은 한 항목을 쉼표로 다시 나눈다 — 조각마다 검사한다
            for (String origin : (pattern == null ? "" : pattern).split(",", -1)) {
                if (!EXPLICIT_ORIGIN.matcher(origin.trim()).matches()) {
                    throw new IllegalArgumentException(
                            "proovy.cors.allowed-origin-patterns must list explicit origins (scheme://host[:port|:[*]]): "
                                    + origin.trim());
                }
            }
        }
        allowedOriginPatterns = List.copyOf(allowedOriginPatterns);
    }
}
