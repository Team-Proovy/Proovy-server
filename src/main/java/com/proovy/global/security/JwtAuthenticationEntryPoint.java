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

        if (errorCode == null) {
            errorCode = ErrorCode.AUTH4010;
        }

        log.warn("인증 실패 - URI: {}, 에러: {}", request.getRequestURI(), errorCode.getCode());

        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setStatus(errorCode.getHttpStatus().value());

        ApiResponse<?> errorResponse = ApiResponse.failure(errorCode);
        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }
}
