package com.proovy.domain.note.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GenerateTitleRequest(
        @NotBlank(message = "질문 내용을 입력해주세요.")
        @Size(max = 2000, message = "질문 내용은 2000자 이내로 입력해주세요.")
        String text
) {
}
