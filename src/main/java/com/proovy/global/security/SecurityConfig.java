package com.proovy.global.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(CorsProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String RUN_CREATE_PATH = "/api/conversations";
    private static final String RUN_EVENTS_PREFIX = "/api/ai-runs/";
    private static final String RUN_EVENTS_SUFFIX = "/events";

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final JwtAuthenticationEntryPoint jwtAuthEntryPoint;
    private final CorsProperties corsProperties;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception ->
                        exception.authenticationEntryPoint(jwtAuthEntryPoint))
                .authorizeHttpRequests(auth -> auth
                        // 인증 없이 접근 가능
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/auth/logout").authenticated()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/api-docs/**", "/v3/api-docs/**").permitAll()
                    .requestMatchers("/health", "/actuator/health").permitAll()
                    // 에러 디스패치(/error) 시에는 인증 필터가 개입하지 않도록 허용
                    .requestMatchers("/error").permitAll()
                        // run SSE(1.86): 인증·권한은 최초 요청에서 끝났다. 응답 완료 시의 ASYNC 재dispatch에는 JWT filter가
                        // 돌지 않아(OncePerRequestFilter) 인증 정보가 없으므로, 이미 열린 run stream 경로만 통과시킨다.
                        .requestMatchers(SecurityConfig::isRunStreamAsyncDispatch).permitAll()
                        // 나머지는 인증 필요
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /** browser-run-v1 SSE 경로(POST /api/conversations, GET /api/ai-runs/{runId}/events)의 ASYNC dispatch. */
    static boolean isRunStreamAsyncDispatch(HttpServletRequest request) {
        if (request.getDispatcherType() != DispatcherType.ASYNC) {
            return false;
        }
        String path = request.getServletPath();
        return RUN_CREATE_PATH.equals(path)
                || (path.startsWith(RUN_EVENTS_PREFIX) && path.endsWith(RUN_EVENTS_SUFFIX)
                && path.indexOf('/', RUN_EVENTS_PREFIX.length()) == path.length() - RUN_EVENTS_SUFFIX.length());
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        return corsConfigurationSource(corsProperties);
    }

    /** 허용 origin은 {@code proovy.cors.allowed-origin-patterns}(기본·운영 https://proovy.ai.kr, local profile은 localhost 추가). */
    static CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(properties.allowedOriginPatterns());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
