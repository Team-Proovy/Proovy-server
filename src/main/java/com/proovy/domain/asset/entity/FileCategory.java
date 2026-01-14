package com.proovy.domain.asset.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum FileCategory {
    IMAGE("image"),
    DOCUMENT("document"),
    CODE("code"),
    OTHER("other");

    private final String value;

    @JsonValue
    public String getValue() {
        return value;
    }

    public static FileCategory fromMimeType(String mimeType) {
        if (mimeType == null) {
            return OTHER;
        }

        // 이미지 파일
        if (mimeType.startsWith("image/")) {
            return IMAGE;
        }

        // 문서 파일 (PDF)
        if (mimeType.equals("application/pdf")) {
            return DOCUMENT;
        }

        // 코드 파일
        if (isCodeMimeType(mimeType)) {
            return CODE;
        }

        return OTHER;
    }

    private static boolean isCodeMimeType(String mimeType) {
        return mimeType.startsWith("text/x-") ||
               mimeType.contains("javascript") ||
               mimeType.contains("typescript") ||
               mimeType.contains("java") ||
               mimeType.contains("python") ||
               mimeType.equals("application/json") ||
               mimeType.equals("text/plain") ||
               mimeType.equals("text/html") ||
               mimeType.equals("text/css");
    }

    /**
     * 썸네일 제공 여부
     * image, document 카테고리만 썸네일 제공
     */
    public boolean hasThumbnail() {
        return this == IMAGE || this == DOCUMENT;
    }
}
