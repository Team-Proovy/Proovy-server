package com.proovy.domain.run.controller;

import com.proovy.global.exception.BusinessException;
import com.proovy.global.response.ApiResponse;
import com.proovy.global.response.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * {@link BrowserRunController}의 스트림 시작 전 거부를 JSON {@code ApiResponse}로 답한다. Content-Type을 미리 정하므로
 * {@code Accept: text/event-stream} 요청에도 JSON이다(browser-run-v1 "스트림 시작 전 오류만 JSON").
 * 전역 {@code GlobalExceptionHandler}는 SSE Accept면 오류 JSON을 {@code text/event-stream}으로 감싸므로 이 controller에는 쓰지 않는다.
 * 접수 거부({@code RunAdmissionRejectedException})는 {@code RunAdmissionExceptionHandler}가 먼저 처리한다.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RestControllerAdvice(assignableTypes = BrowserRunController.class)
public class BrowserRunExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Object>> rejected(BusinessException e) {
        log.info("browser run request rejected: {}", e.getErrorCode().getCode());
        return failure(e.getErrorCode());
    }

    /** 읽을 수 없는 JSON·지원하지 않는 Content-Type의 POST body는 구조 위반(RUN4001)이다. */
    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<ApiResponse<Object>> unreadable(Exception e) {
        log.info("browser run request body unreadable: {}", e.getClass().getSimpleName());
        return failure(ErrorCode.RUN4001);
    }

    /**
     * 브라우저가 SSE 연결을 끊었다. 쓸 응답이 없고 오류도 아니다(구독은 stream이 이미 해제한다). 전역 catch-all이 ERROR로
     * 기록하고 {@code text/event-stream}에 JSON을 쓰려다 실패하지 않게 여기서 끝낸다.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void clientGone(AsyncRequestNotUsableException e) {
        log.debug("browser run SSE client disconnected");
    }

    private static ResponseEntity<ApiResponse<Object>> failure(ErrorCode code) {
        return ResponseEntity.status(code.getHttpStatus()).contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure(code));
    }
}
