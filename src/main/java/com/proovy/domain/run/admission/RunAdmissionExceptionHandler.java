package com.proovy.domain.run.admission;

import com.proovy.global.response.ApiResponse;
import com.proovy.global.response.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 접수 거부를 browser-run-v1 오류 응답으로 바꾼다. 스트림 시작 전 오류는 {@code Accept: text/event-stream} 요청이어도
 * JSON {@link ApiResponse}다(CATALOG "스트림 시작 전 오류만 JSON"). RUN4091은 result에 activeRunId, RUN4291은 Retry-After.
 * {@code GlobalExceptionHandler}의 BusinessException 처리보다 먼저 고른다.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class RunAdmissionExceptionHandler {

    @ExceptionHandler(RunAdmissionRejectedException.class)
    public ResponseEntity<ApiResponse<Object>> handle(RunAdmissionRejectedException e) {
        ErrorCode code = e.getErrorCode();
        log.info("run admission rejected: {}", code.getCode());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(code.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON);
        e.retryAfterSeconds().ifPresent(seconds -> response.header(HttpHeaders.RETRY_AFTER, Long.toString(seconds)));
        return response.body(e.activeRun().<ApiResponse<Object>>map(conflict -> ApiResponse.failure(code, conflict))
                .orElseGet(() -> ApiResponse.failure(code)));
    }
}
