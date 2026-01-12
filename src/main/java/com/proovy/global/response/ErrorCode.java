package com.proovy.global.response;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Common
    COMMON200("COMMON200", "요청에 성공했습니다."),
    COMMON400("COMMON400", "잘못된 요청입니다."),
    COMMON500("SERVER5001", "서버 내부 오류가 발생했습니다."),

    // Auth
    AUTH4011("AUTH4011", "인증이 필요합니다."),
    AUTH4012("AUTH4012", "토큰이 만료되었습니다."),
    AUTH4013("AUTH4013", "권한이 없습니다."),

    // User
    USER4041("USER4041", "사용자를 찾을 수 없습니다."),
    USER4091("USER4091", "이미 존재하는 사용자입니다."),

    // Storage
    STORAGE4001("STORAGE4001", "파일을 찾을 수 없습니다."),
    STORAGE4002("STORAGE4002", "스토리지 용량이 부족합니다."),
    STORAGE4003("STORAGE4003", "검색어는 최소 2자 이상부터 입력 가능합니다."),
    STORAGE4004("STORAGE4004", "허용되지 않은 파일 형식입니다."),

    // Note
    NOTE4041("NOTE4041", "노트를 찾을 수 없습니다."),
    NOTE4031("NOTE4031", "노트 접근 권한이 없습니다.");

    private final String code;
    private final String message;
}
