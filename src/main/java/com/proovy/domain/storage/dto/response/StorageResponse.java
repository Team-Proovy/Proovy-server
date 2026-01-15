package com.proovy.domain.storage.dto.response;

import lombok.Builder;

import java.util.List;

@Builder
public record StorageResponse(
        Integer totalUsed,
        Integer totalLimit,
        String totalUsedDisplay,
        String totalLimitDisplay,
        Integer usagePercent,
        PlanDto plan,
        List<NoteStorageDto> notes
) {
    public static StorageResponse of(
            int totalUsedMb,
            int totalLimitMb,
            String planType,
            boolean isActive,
            List<NoteStorageDto> notes
    ) {
        int usagePercent = totalLimitMb > 0
                ? Math.round((float) totalUsedMb / totalLimitMb * 100)
                : 0;

        return StorageResponse.builder()
                .totalUsed(totalUsedMb)
                .totalLimit(totalLimitMb)
                .totalUsedDisplay(formatStorage(totalUsedMb))
                .totalLimitDisplay(formatStorage(totalLimitMb))
                .usagePercent(usagePercent)
                .plan(new PlanDto(planType, isActive))
                .notes(notes)
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
