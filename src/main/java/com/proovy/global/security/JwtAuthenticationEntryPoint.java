package com.proovy.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.proovy.global.response.ApiResponse;
import com.proovy.global.response.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {
        ErrorCode errorCode = (ErrorCode) request.getAttribute(JwtAuthenticationFilter.JWT_ERROR_ATTRIBUTE);

        // JwtAuthenticationFilter 에서 명시적으로 설정한 오류 코드가 없다면,
        // Authorization 헤더 존재 여부를 기준으로 보다 정확한 기본 코드를 결정한다.
        if (errorCode == null) {
            String authHeader = request.getHeader("Authorization");

            // 헤더가 아예 없으면 "토큰이 필요"한 상황으로 간주 (AUTH4010)
            if (authHeader == null || authHeader.isBlank()) {
                errorCode = ErrorCode.AUTH4010;
            } else {
                // 헤더는 있는데도 Authentication 실패가 났다면
                // "유효하지 않은 토큰" 상황으로 간주 (AUTH4013)
                errorCode = ErrorCode.AUTH4013;
            }
        }

        log.warn("인증 실패 - URI: {}, 에러: {}", request.getRequestURI(), errorCode.getCode());

        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setStatus(errorCode.getHttpStatus().value());

        ApiResponse<?> errorResponse = ApiResponse.failure(errorCode);
        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }
}
