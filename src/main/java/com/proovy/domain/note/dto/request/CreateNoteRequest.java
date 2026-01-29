package com.proovy.domain.note.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateNoteRequest(
        @NotBlank(message = "메시지 내용을 입력해주세요.")
        @Size(max = 5000, message = "메시지는 5000자 이내로 입력해주세요.")
        String firstMessage,

        List<Long> mentionedAssetIds,

        List<String> mentionedToolCodes
) {
    public CreateNoteRequest {
        mentionedAssetIds = mentionedAssetIds != null ? mentionedAssetIds : List.of();
        mentionedToolCodes = mentionedToolCodes != null ? mentionedToolCodes : List.of();
    }
}

