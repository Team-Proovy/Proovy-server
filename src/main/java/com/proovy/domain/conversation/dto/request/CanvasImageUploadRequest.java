package com.proovy.domain.conversation.dto.request;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CanvasImageUploadRequest {

    @NotNull(message = "노트 ID는 필수입니다.")
    private Long noteId;

    @NotBlank(message = "파일명은 필수입니다.")
    @Size(min = 2, max = 255, message = "파일명은 2자 이상 255자 이하로 입력해주세요.")
    private String fileName;

    @NotBlank(message = "MIME 타입은 필수입니다.")
    private String mimeType;

    @NotNull(message = "파일 크기는 필수입니다.")
    @Min(value = 1, message = "파일 크기는 1 이상이어야 합니다.")
    private Long fileSize;

    public CanvasImageUploadRequest(Long noteId, String fileName, String mimeType, Long fileSize) {
        this.noteId = noteId;
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.fileSize = fileSize;
    }
}
