package com.proovy.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.proovy.domain.auth.service.AccessTokenBlacklistService;
import com.proovy.domain.auth.service.JwtTokenProvider;
import com.proovy.domain.user.entity.User;
import com.proovy.domain.user.repository.UserRepository;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.response.ApiResponse;
import com.proovy.global.response.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
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
    private final AccessTokenBlacklistService accessTokenBlacklistService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    public static final String JWT_ERROR_ATTRIBUTE = "jwtError";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {

        String token = resolveToken(request);
        log.debug("[JWT] 요청 URI: {}, 토큰 존재: {}", request.getRequestURI(), token != null);

        if (token == null) {
            request.setAttribute(JWT_ERROR_ATTRIBUTE, ErrorCode.AUTH4010);
            chain.doFilter(request, response);
            return;
        }

        try {
            if (accessTokenBlacklistService.isBlacklisted(token)) {
                throw new BusinessException(ErrorCode.AUTH4013);
            }
            if (jwtTokenProvider.validateAccessToken(token)) {
                Long userId = jwtTokenProvider.getUserIdFromToken(token);

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
                log.debug("[JWT] 인증 완료");
            }
        } catch (BusinessException e) {
            log.warn("[JWT] 인증 실패 - {}: {}", e.getErrorCode().getCode(), e.getMessage());
            sendErrorResponse(response, e.getErrorCode());
            return;
        } catch (Exception e) {
            log.warn("[JWT] 예외 발생: {}", e.getMessage());
            sendErrorResponse(response, ErrorCode.AUTH4013);
            return;
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

    private void sendErrorResponse(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setStatus(errorCode.getHttpStatus().value());

        ApiResponse<?> errorResponse = ApiResponse.failure(errorCode);
        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }
}
