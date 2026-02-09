package com.proovy.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.proovy.global.response.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class InternalApiTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String INTERNAL_PATH = "/internal";
    private static final String INTERNAL_PATH_PREFIX = "/internal/";
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final ObjectMapper objectMapper;

    @Value("${internal.api.token}")
    private String internalApiToken;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null) {
            return true;
        }
        return !(INTERNAL_PATH.equals(path) || path.startsWith(INTERNAL_PATH_PREFIX));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestToken = request.getHeader(INTERNAL_TOKEN_HEADER);
        if (requestToken == null || !internalApiToken.equals(requestToken)) {
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            ApiResponse<?> errorResponse = ApiResponse.failure("INTERNAL4031", "유효하지 않은 내부 API 토큰입니다.");
            response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
            return;
        }

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        "internal-api",
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_INTERNAL"))
                );
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }
}
