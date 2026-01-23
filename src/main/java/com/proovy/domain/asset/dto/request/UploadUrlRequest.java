package com.proovy.domain.asset.dto.request;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class UploadUrlRequest {

    @NotNull(message = "노트 ID는 필수입니다.")
    private Long noteId;

    @NotBlank(message = "파일명은 필수입니다.")
    @Size(min = 2, message = "파일명은 최소 2자 이상 입력해주세요.")
    private String fileName;

    @NotBlank(message = "MIME 타입은 필수입니다.")
    private String mimeType;

    @NotNull(message = "파일 크기는 필수입니다.")
    @Min(value = 1, message = "파일 크기는 1 이상이어야 합니다.")
    @Max(value = 31457280, message = "파일 크기는 30MB를 초과할 수 없습니다.")
    private Long fileSize;

    // 테스트용 생성자
    public UploadUrlRequest(Long noteId, String fileName, String mimeType, Long fileSize) {
        this.noteId = noteId;
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.fileSize = fileSize;
    }
}
