package com.proovy.domain.note.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateNoteTitleRequest(
        @NotBlank(message = "노트 제목을 입력해주세요.")
        @Size(max = 30, message = "노트 제목은 30자 이내로 입력해주세요.")
        String title
) {
}

