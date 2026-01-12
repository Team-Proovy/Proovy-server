package com.proovy.global.exception;

import com.proovy.global.response.ApiResponse;
import com.proovy.global.response.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 비즈니스 예외 처리
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        log.warn("Business exception: {} - {}", e.getErrorCode().getCode(), e.getMessage());

        ErrorCode errorCode = e.getErrorCode();
        HttpStatus status = getHttpStatus(errorCode);

        return ResponseEntity
                .status(status)
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
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failure(ErrorCode.COMMON500));
    }

    /**
     * ErrorCode에 따른 HTTP 상태 코드 매핑
     */
    private HttpStatus getHttpStatus(ErrorCode errorCode) {
        String code = errorCode.getCode();

        if (code.endsWith("200")) {
            return HttpStatus.OK;
        } else if (code.endsWith("400") || code.contains("4001") || code.contains("4002") || code.contains("4003") || code.contains("4004")) {
            return HttpStatus.BAD_REQUEST;
        } else if (code.contains("401")) {
            return HttpStatus.UNAUTHORIZED;
        } else if (code.contains("403")) {
            return HttpStatus.FORBIDDEN;
        } else if (code.contains("404")) {
            return HttpStatus.NOT_FOUND;
        } else if (code.contains("409")) {
            return HttpStatus.CONFLICT;
        } else if (code.contains("500")) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }

        return HttpStatus.BAD_REQUEST;
    }
}
