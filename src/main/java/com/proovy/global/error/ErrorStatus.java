package com.proovy.global.error;

import org.springframework.http.HttpStatus;

public enum ErrorStatus {
    _PARSING_ERROR(HttpStatus.BAD_REQUEST, "AUTH400", "Kakao 응답 파싱에 실패했습니다."),
    _KAKAO_API_ERROR(HttpStatus.BAD_GATEWAY, "AUTH502", "Kakao API 호출에 실패했습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    ErrorStatus(HttpStatus httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    public HttpStatus getHttpStatus() { return httpStatus; }
    public String getCode() { return code; }
    public String getMessage() { return message; }
}
