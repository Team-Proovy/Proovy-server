package com.proovy.domain.storage.dto.response;

import java.util.List;

public record BulkDeleteResponse(
        Integer deletedCount,
        List<Long> deletedAssetIds
) {
    public static BulkDeleteResponse of(List<Long> deletedAssetIds) {
        return new BulkDeleteResponse(
                deletedAssetIds.size(),
                deletedAssetIds
        );
    }
}
