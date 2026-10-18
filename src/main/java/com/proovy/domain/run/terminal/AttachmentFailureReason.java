package com.proovy.domain.run.terminal;

import com.proovy.domain.run.entity.WireValue;

/**
 * {@code run.failed}의 {@code invalid_attachment} reason sub-code(Brain {@code AttachmentFailureReason})와
 * reason별 고정 안전 message. 파일 이름·URL·측정값은 담지 않는다.
 */
public enum AttachmentFailureReason implements WireValue {
    UNSUPPORTED_MEDIA_TYPE("unsupported_media_type",
            "지원하지 않는 첨부 형식입니다. PDF, JPEG, PNG, WebP 파일로 다시 올려주세요."),
    SIGNATURE_MISMATCH("signature_mismatch", "첨부 파일의 형식과 내용이 일치하지 않습니다. 원본 파일로 다시 올려주세요."),
    FILE_TOO_LARGE("file_too_large", "첨부 파일의 용량이 한도를 초과했습니다. 용량을 줄여 다시 올려주세요."),
    IMAGE_DECODE_FAILED("image_decode_failed", "손상됐거나 읽을 수 없는 이미지입니다. 다른 파일로 다시 올려주세요."),
    ABNORMAL_DIMENSIONS("abnormal_dimensions", "이미지 크기가 비정상적입니다. 크기를 조정한 파일로 다시 올려주세요."),
    PIXEL_LIMIT_EXCEEDED("pixel_limit_exceeded", "이미지 해상도가 한도를 초과했습니다. 해상도를 줄여 다시 올려주세요."),
    PDF_ENCRYPTED("pdf_encrypted", "암호로 보호된 PDF는 처리할 수 없습니다. 암호를 해제한 PDF를 다시 올려주세요."),
    PDF_CORRUPT("pdf_corrupt", "손상됐거나 읽을 수 없는 PDF입니다. 다른 파일로 다시 올려주세요."),
    TOO_MANY_PAGES("too_many_pages", "PDF 페이지 수가 한도를 초과했습니다. 문서를 나누어 다시 올려주세요."),
    PAGE_OUT_OF_RANGE("page_out_of_range", "요청한 페이지가 문서에 없습니다. 페이지 범위를 확인해 다시 요청해 주세요."),
    BUNDLE_COUNT_EXCEEDED("bundle_count_exceeded",
            "한 번에 첨부할 수 있는 파일 개수를 초과했습니다. 개수를 줄여 다시 올려주세요."),
    BUNDLE_BYTES_EXCEEDED("bundle_bytes_exceeded", "첨부 파일의 총 용량이 한도를 초과했습니다. 용량을 줄여 다시 올려주세요."),
    BUNDLE_EMPTY("bundle_empty", "첨부된 파일이 없습니다. 파일을 첨부해 다시 요청해 주세요.");

    private final String wireValue;
    private final String message;

    AttachmentFailureReason(String wireValue, String message) {
        this.wireValue = wireValue;
        this.message = message;
    }

    @Override
    public String wireValue() {
        return wireValue;
    }

    public String message() {
        return message;
    }
}
