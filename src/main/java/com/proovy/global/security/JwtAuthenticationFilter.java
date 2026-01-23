package com.proovy.global.security;

import com.proovy.domain.auth.service.JwtTokenProvider;
import com.proovy.domain.user.entity.User;
import com.proovy.domain.user.repository.UserRepository;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.response.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {

        try {
            String token = resolveToken(request);
            log.info("[JWT] 요청 URI: {}, 토큰 존재: {}", request.getRequestURI(), token != null);

            if (token != null) {
                log.info("[JWT] 토큰 검증 시작...");
                if (jwtTokenProvider.validateAccessToken(token)) {
                    Long userId = jwtTokenProvider.getUserIdFromToken(token);
                    log.info("[JWT] 토큰 검증 성공, userId: {}", userId);

                    User user = userRepository.findById(userId)
                            .orElseThrow(() -> new BusinessException(ErrorCode.USER4041));

                    UserPrincipal principal = new UserPrincipal(user);
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    principal, null, principal.getAuthorities()
                            );
                    authentication.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request)
                    );

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    log.info("[JWT] 인증 완료, userId: {}", userId);
                }
            }
        } catch (BusinessException e) {
            log.warn("[JWT] 인증 실패: {}", e.getMessage());
        } catch (Exception e) {
            log.error("[JWT] 예외 발생: {}", e.getMessage(), e);
        }

        chain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader(AUTHORIZATION_HEADER);
        if (bearerToken != null && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
