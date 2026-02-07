package com.proovy.global.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.proovy.global.response.ApiResponse;
import com.proovy.global.response.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final ObjectMapper objectMapper;

    /**
     * 비즈니스 예외 처리
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<?> handleBusinessException(BusinessException e, HttpServletRequest request) {
        log.warn("Business exception: {} - {}", e.getErrorCode().getCode(), e.getMessage());

        ErrorCode errorCode = e.getErrorCode();

        String accept = request.getHeader("Accept");

        // SSE 엔드포인트(text/event-stream)에서 발생한 비즈니스 예외는
        // 클라이언트가 수용 가능한 media type 내에서 에러를 내려주기 위해
        // text/event-stream 으로 JSON 문자열을 감싸서 반환한다.
        if (accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            try {
                String body = objectMapper.writeValueAsString(ApiResponse.failure(errorCode));
                return ResponseEntity
                        .status(errorCode.getHttpStatus())
                        .contentType(MediaType.TEXT_EVENT_STREAM)
                        .body(body);
            } catch (Exception ex) {
                log.error("Failed to serialize ApiResponse for BusinessException", ex);
                return ResponseEntity
                        .status(errorCode.getHttpStatus())
                        .contentType(MediaType.TEXT_EVENT_STREAM)
                        .body("{\"code\":\"" + errorCode.getCode() + "\",\"message\":\"" + e.getMessage() + "\"}");
            }
        }

        // 그 외 일반 요청은 기존처럼 JSON ApiResponse 로 응답
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ApiResponse.failure(errorCode));
    }

    /**
     * Validation 예외 처리
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : "입력값이 올바르지 않습니다.";

        log.warn("Validation exception: {}", message);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.failure("COMMON400", message));
    }

    /**
     * 기타 예외 처리
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("Unexpected exception occurred", e);

        return ResponseEntity
                .status(ErrorCode.COMMON500.getHttpStatus())
                .body(ApiResponse.failure(ErrorCode.COMMON500));
    }
}
