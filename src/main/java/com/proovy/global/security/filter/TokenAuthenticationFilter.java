package com.proovy.global.security.filter;

import com.proovy.infrastructure.jwt.TokenKey;
import com.proovy.infrastructure.jwt.TokenProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;

@RequiredArgsConstructor
@Component
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    private final TokenProvider tokenProvider;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();

        return path.equals("/favicon.ico")
                || path.equals("/swagger-ui.html")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-resources")
                || path.startsWith("/webjars")
                || path.equals("/error");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String accessToken = resolveToken(request);

        // 토큰이 없으면 검증/재발급 시도하지 말고 그냥 통과
        if (!StringUtils.hasText(accessToken)) {
            filterChain.doFilter(request, response);
            return;
        }

        // accessToken 검증
        if (tokenProvider.validateToken(accessToken)) {
            setAuthentication(accessToken);
        } else {
            // 만료되었을 경우 accessToken 재발급
            String reissueAccessToken = tokenProvider.reissueAccessToken(accessToken);

            if (StringUtils.hasText(reissueAccessToken)) {
                setAuthentication(reissueAccessToken);

                // 재발급된 accessToken 다시 전달
                response.setHeader(AUTHORIZATION, TokenKey.TOKEN_PREFIX + reissueAccessToken);
            }
        }

        filterChain.doFilter(request, response);
    }

    private void setAuthentication(String accessToken) {
        Authentication authentication = tokenProvider.getAuthentication(accessToken);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private String resolveToken(HttpServletRequest request) {
        String token = request.getHeader(AUTHORIZATION);
        if (ObjectUtils.isEmpty(token) || !token.startsWith(TokenKey.TOKEN_PREFIX)) {
            return null;
        }
        return token.substring(TokenKey.TOKEN_PREFIX.length());
    }
}
