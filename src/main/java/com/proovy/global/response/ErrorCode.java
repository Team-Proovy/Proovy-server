package com.proovy.global.response;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Common
    COMMON200("COMMON200", "요청에 성공했습니다.", HttpStatus.OK),
    COMMON400("COMMON400", "잘못된 요청입니다.", HttpStatus.BAD_REQUEST),
    COMMON500("COMMON500", "서버 내부 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),

    // Auth
    AUTH4001("AUTH4001", "Redirect URI가 일치하지 않습니다.", HttpStatus.BAD_REQUEST),
    AUTH4002("AUTH4002", "state 값이 일치하지 않습니다.", HttpStatus.UNAUTHORIZED),
    AUTH4011("AUTH4011", "유효하지 않은 인증 코드입니다.", HttpStatus.UNAUTHORIZED),
    AUTH4012("AUTH4012", "토큰이 만료되었습니다.", HttpStatus.UNAUTHORIZED),
    AUTH4013("AUTH4013", "유효하지 않은 토큰입니다.", HttpStatus.UNAUTHORIZED),
    AUTH4291("AUTH4291", "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.", HttpStatus.TOO_MANY_REQUESTS),
    AUTH5021("AUTH5021", "카카오 서버 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),
    AUTH5022("AUTH5022", "네이버 서버 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),
    AUTH5023("AUTH5023", "구글 서버 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),

            // User
    USER4041("USER4041", "사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    USER4091("USER4091", "이미 존재하는 사용자입니다.", HttpStatus.CONFLICT),

    // Storage / Asset
    STORAGE4001("STORAGE4001", "파일을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    STORAGE4002("STORAGE4002", "스토리지 용량이 부족합니다.", HttpStatus.BAD_REQUEST),
    STORAGE4003("STORAGE4003", "검색어는 최소 2자 이상부터 입력 가능합니다.", HttpStatus.BAD_REQUEST),
    STORAGE4004("STORAGE4004", "허용되지 않은 파일 형식입니다.", HttpStatus.BAD_REQUEST),
    STORAGE4031("STORAGE4031", "자산 접근 권한이 없습니다.", HttpStatus.FORBIDDEN),

    // Note
    NOTE4041("NOTE4041", "노트를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    NOTE4031("NOTE4031", "노트 접근 권한이 없습니다.", HttpStatus.FORBIDDEN),

    ASSET4001("ASSET4001", "지원하지 않는 파일 형식입니다. PDF, PNG, JPEG, WEBP만 업로드 가능합니다.", HttpStatus.BAD_REQUEST),
    ASSET4002("ASSET4002", "파일 크기가 30MB를 초과합니다.", HttpStatus.BAD_REQUEST),
    ASSET4005("ASSET4005", "파일명은 2자 이상 255자 이하로 입력해주세요.", HttpStatus.BAD_REQUEST),
    STORAGE4005("STORAGE4005", "노트의 스토리지 용량(500MB)을 초과합니다.", HttpStatus.FORBIDDEN);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
