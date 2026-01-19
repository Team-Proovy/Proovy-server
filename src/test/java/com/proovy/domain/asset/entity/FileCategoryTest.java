package com.proovy.domain.asset.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class FileCategoryTest {

    @Test
    @DisplayName("이미지 MIME 타입은 IMAGE 카테고리로 분류된다")
    void imageCategory() {
        assertThat(FileCategory.fromMimeType("image/png")).isEqualTo(FileCategory.IMAGE);
        assertThat(FileCategory.fromMimeType("image/jpeg")).isEqualTo(FileCategory.IMAGE);
        assertThat(FileCategory.fromMimeType("image/gif")).isEqualTo(FileCategory.IMAGE);
        assertThat(FileCategory.fromMimeType("image/webp")).isEqualTo(FileCategory.IMAGE);
    }

    @Test
    @DisplayName("PDF MIME 타입은 DOCUMENT 카테고리로 분류된다")
    void documentCategory() {
        assertThat(FileCategory.fromMimeType("application/pdf")).isEqualTo(FileCategory.DOCUMENT);
    }

    @ParameterizedTest
    @DisplayName("코드 MIME 타입은 CODE 카테고리로 분류된다")
    @ValueSource(strings = {
            "text/x-python",
            "text/x-java",
            "text/javascript",
            "application/javascript",
            "application/json",
            "text/plain",
            "text/html",
            "text/css"
    })
    void codeCategory(String mimeType) {
        assertThat(FileCategory.fromMimeType(mimeType)).isEqualTo(FileCategory.CODE);
    }

    @ParameterizedTest
    @DisplayName("알 수 없는 MIME 타입은 OTHER 카테고리로 분류된다")
    @ValueSource(strings = {
            "application/zip",
            "application/octet-stream",
            "video/mp4",
            "audio/mpeg"
    })
    void otherCategory(String mimeType) {
        assertThat(FileCategory.fromMimeType(mimeType)).isEqualTo(FileCategory.OTHER);
    }

    @ParameterizedTest
    @DisplayName("null MIME 타입은 OTHER 카테고리로 분류된다")
    @NullSource
    void nullMimeType(String mimeType) {
        assertThat(FileCategory.fromMimeType(mimeType)).isEqualTo(FileCategory.OTHER);
    }

    @ParameterizedTest
    @DisplayName("IMAGE, DOCUMENT 카테고리만 썸네일을 제공한다")
    @CsvSource({
            "IMAGE, true",
            "DOCUMENT, true",
            "CODE, false",
            "OTHER, false"
    })
    void hasThumbnail(FileCategory category, boolean expected) {
        assertThat(category.hasThumbnail()).isEqualTo(expected);
    }

    @Test
    @DisplayName("JSON 직렬화 시 소문자 값을 반환한다")
    void jsonValue() {
        assertThat(FileCategory.IMAGE.getValue()).isEqualTo("image");
        assertThat(FileCategory.DOCUMENT.getValue()).isEqualTo("document");
        assertThat(FileCategory.CODE.getValue()).isEqualTo("code");
        assertThat(FileCategory.OTHER.getValue()).isEqualTo("other");
    }
}
