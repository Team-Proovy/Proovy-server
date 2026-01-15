package com.proovy.global.security.filter;

import com.proovy.infrastructure.jwt.TokenException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

public class TokenExceptionFilter extends OncePerRequestFilter {

    // swagger/정적 리소스 요청은 토큰 예외처리 필터에서 제외
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();

        return path.equals("/favicon.ico")
                || path.equals("/swagger-ui.html")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/swagger-resources")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/api-docs")
                || path.startsWith("/webjars")
                || path.equals("/error")
                || path.endsWith(".map");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        try {
            filterChain.doFilter(request, response);
        } catch (TokenException e) {
            response.sendError(e.getErrorCode().getHttpStatus().value(), e.getMessage());
        }
    }
}
