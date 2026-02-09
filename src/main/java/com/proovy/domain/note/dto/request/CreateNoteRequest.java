package com.proovy.domain.note.dto.request;

import jakarta.validation.constraints.Size;

public record CreateNoteRequest(
        @Size(max = 200, message = "노트 제목은 200자 이내로 입력해주세요.")
        String title
) {
}

