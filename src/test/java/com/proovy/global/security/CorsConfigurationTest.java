package com.proovy.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * 실제 application.yaml을 profile별로 읽어 CORS 허용 origin을 확인한다. local profile만 개발 Front(localhost, 포트 무관)를
 * 허용하고, 운영(prod)·CI(test) profile은 https://proovy.ai.kr만 허용한다. 실행 환경의 OS 환경변수
 * (SPRING_PROFILES_ACTIVE·PROOVY_CORS_ALLOWED_ORIGIN_PATTERNS 등)는 결과에 섞이지 않게 뺀다.
 */
class CorsConfigurationTest {

    private static final String SERVICE_ORIGIN = "https://proovy.ai.kr";
    private static final String DEV_FRONT_ORIGIN = "http://localhost:5196";

    @Configuration
    @EnableConfigurationProperties(CorsProperties.class)
    static class Properties {
    }

    private final ApplicationContextRunner context = new ApplicationContextRunner(() -> {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        ctx.setEnvironment(environment);
        return ctx;
    })
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Properties.class);

    @Test
    @DisplayName("local profile은 서비스 도메인과 localhost의 모든 포트를 허용한다")
    void localProfileAllowsLocalhostOrigins() {
        context.withPropertyValues("spring.profiles.active=local").run(ctx -> {
            assertThat(ctx.getEnvironment().getActiveProfiles()).containsExactly("local");
            CorsConfigurationSource source = SecurityConfig.corsConfigurationSource(ctx.getBean(CorsProperties.class));

            assertThat(post(source, DEV_FRONT_ORIGIN).getStatus()).isEqualTo(200);
            assertThat(post(source, "http://localhost:5173").getStatus()).isEqualTo(200);
            assertThat(post(source, SERVICE_ORIGIN).getStatus()).isEqualTo(200);
            assertThat(post(source, "http://localhost.attacker.example").getStatus()).isEqualTo(403);
            assertThat(post(source, "https://evil.example").getStatus()).isEqualTo(403);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "test"})
    @DisplayName("운영·CI profile은 서비스 도메인만 허용하고 localhost POST를 거부한다")
    void otherProfilesAllowServiceOriginOnly(String profile) {
        context.withPropertyValues("spring.profiles.active=" + profile).run(ctx -> {
            assertThat(ctx.getEnvironment().getActiveProfiles()).containsExactly(profile);
            CorsProperties properties = ctx.getBean(CorsProperties.class);
            assertThat(properties.allowedOriginPatterns()).containsExactly(SERVICE_ORIGIN);
            CorsConfigurationSource source = SecurityConfig.corsConfigurationSource(properties);

            assertThat(post(source, SERVICE_ORIGIN).getStatus()).isEqualTo(200);
            assertThat(post(source, DEV_FRONT_ORIGIN).getStatus()).isEqualTo(403);
        });
    }

    @Test
    @DisplayName("credentials 허용이라 빈 목록·host wildcard(쉼표로 숨긴 항목 포함)는 기동에서 거부한다")
    void rejectsEmptyOrWildcardOrigins() {
        assertThatThrownBy(() -> new CorsProperties(List.of())).isInstanceOf(IllegalArgumentException.class);
        for (String unsafe : List.of("*", "https://*", "http://*:[*]", "https://*.example.com", SERVICE_ORIGIN + ", *",
                "", "proovy.ai.kr", "https://proovy.ai.kr/")) {
            assertThatThrownBy(() -> new CorsProperties(List.of(SERVICE_ORIGIN, unsafe)))
                    .as(unsafe)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        context.withPropertyValues("spring.profiles.active=prod", "proovy.cors.allowed-origin-patterns=*")
                .run(ctx -> assertThat(ctx).hasFailed());
        context.withPropertyValues("spring.profiles.active=prod", "proovy.cors.allowed-origin-patterns[0]=https://*")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("명시 origin과 포트 wildcard, 쉼표로 나열한 명시 origin은 받는다")
    void acceptsExplicitOrigins() {
        CorsProperties properties = new CorsProperties(List.of(SERVICE_ORIGIN, "http://localhost:[*]",
                "http://localhost:5173, http://127.0.0.1:8080"));
        assertThat(properties.allowedOriginPatterns()).hasSize(3);
    }

    /** Origin을 단 POST가 CORS filter를 통과하면(chain 진행) 200, 거부되면 403. */
    private static MockHttpServletResponse post(CorsConfigurationSource source, String origin) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/conversations");
        request.addHeader("Origin", origin);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new CorsFilter(source).doFilter(request, response, chain);
        if (chain.getRequest() == null) {
            assertThat(response.getStatus()).isEqualTo(403);
        }
        return response;
    }
}
