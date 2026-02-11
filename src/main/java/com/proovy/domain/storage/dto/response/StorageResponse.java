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
            long totalUsedBytes,
            int totalLimitMb,
            String planType,
            boolean isActive,
            List<NoteStorageDto> notes
    ) {
        // totalUsed는 MB 단위로 반올림 (기존 호환성 유지)
        int totalUsedMb = (int) Math.round((double) totalUsedBytes / (1024 * 1024));

        int usagePercent = totalLimitMb > 0
                ? Math.round((float) totalUsedMb / totalLimitMb * 100)
                : 0;

        return StorageResponse.builder()
                .totalUsed(totalUsedMb)
                .totalLimit(totalLimitMb)
                .totalUsedDisplay(formatStorageFromBytes(totalUsedBytes))  // bytes로부터 정확한 소수점 계산
                .totalLimitDisplay(formatStorage(totalLimitMb))
                .usagePercent(usagePercent)
                .plan(new PlanDto(planType, isActive))
                .notes(notes)
                .build();
    }

    /**
     * bytes 단위를 MB 또는 GB로 포맷 (소수점 둘째자리까지)
     */
    private static String formatStorageFromBytes(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);

        if (mb >= 1024) {
            double gb = mb / 1024.0;
            return String.format("%.2fGB", gb);
        }

        // MB 단위일 때도 소수점 표시
        if (mb >= 0.01) {
            return String.format("%.2fMB", mb);
        }

        // 10KB 미만
        return "0MB";
    }

    private static String formatStorage(int mb) {
        if (mb >= 1024) {
            double gb = mb / 1024.0;
            if (gb == Math.floor(gb)) {
                return String.format("%.0fGB", gb);
            }
            return String.format("%.2fGB", gb);
        }
        return mb + "MB";
    }
}
