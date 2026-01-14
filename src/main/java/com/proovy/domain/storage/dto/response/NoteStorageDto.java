package com.proovy.domain.storage.dto.response;

import lombok.Builder;

import java.util.List;

@Builder
public record NoteStorageDto(
        Long noteId,
        String title,
        Integer storageUsed,
        Integer storageLimit,
        String storageUsedDisplay,
        String storageLimitDisplay,
        List<AssetSummaryDto> assets
) {
    private static final int DEFAULT_NOTE_STORAGE_LIMIT_MB = 500;

    public static NoteStorageDto of(
            Long noteId,
            String title,
            int storageUsedMb,
            List<AssetSummaryDto> assets
    ) {
        return NoteStorageDto.builder()
                .noteId(noteId)
                .title(title)
                .storageUsed(storageUsedMb)
                .storageLimit(DEFAULT_NOTE_STORAGE_LIMIT_MB)
                .storageUsedDisplay(formatStorage(storageUsedMb))
                .storageLimitDisplay(formatStorage(DEFAULT_NOTE_STORAGE_LIMIT_MB))
                .assets(assets)
                .build();
    }

    private static String formatStorage(int mb) {
        if (mb >= 1000) {
            double gb = mb / 1000.0;
            if (gb == Math.floor(gb)) {
                return String.format("%.0fGB", gb);
            }
            return String.format("%.2fGB", gb);
        }
        return mb + "MB";
    }
}
