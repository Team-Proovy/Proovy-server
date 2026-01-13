package com.proovy.domain.storage.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record BulkDeleteRequest(
        @NotEmpty(message = "삭제할 자산 ID 목록은 필수입니다")
        @Size(min = 1, max = 30, message = "한 번에 최대 30개까지 삭제 가능합니다")
        List<Long> assetIds
) {
}
