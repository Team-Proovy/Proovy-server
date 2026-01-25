package com.proovy.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private final Boolean isSuccess;
    private final String code;
    private final String message;
    private final T result;

    private ApiResponse(Boolean isSuccess, String code, String message, T result) {
        this.isSuccess = isSuccess;
        this.code = code;
        this.message = message;
        this.result = result;
    }

    // 성공 응답 (데이터 있음)
    public static <T> ApiResponse<T> success(T result) {
        return new ApiResponse<>(true, "COMMON200", "요청에 성공했습니다.", result);
    }

    // 성공 응답 (데이터 없음)
    public static <T> ApiResponse<T> success() {
        return new ApiResponse<>(true, "COMMON200", "요청에 성공했습니다.", null);
    }

    // 성공 응답 (커스텀 메시지)
    public static <T> ApiResponse<T> success(String message, T result) {
        return new ApiResponse<>(true, "COMMON200", message, result);
    }

    // 성공 응답 (커스텀 코드 + 메시지)
    public static <T> ApiResponse<T> of(String code, String message, T result) {
        return new ApiResponse<>(true, code, message, result);
    }

    // 실패 응답
    public static <T> ApiResponse<T> failure(String code, String message) {
        return new ApiResponse<>(false, code, message, null);
    }

    // 실패 응답 (ErrorCode 사용)
    public static <T> ApiResponse<T> failure(ErrorCode errorCode) {
        return new ApiResponse<>(false, errorCode.getCode(), errorCode.getMessage(), null);
    }
}
