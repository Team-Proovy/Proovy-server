package com.proovy.domain.run.brain;

import static com.proovy.domain.run.brain.InternalRunContract.MAX_ASSET_BYTES;
import static com.proovy.domain.run.brain.InternalRunContract.MAX_ASSET_PAGES;
import static com.proovy.domain.run.brain.InternalRunContract.MAX_ASSET_PIXEL_COUNT;
import static com.proovy.domain.run.brain.InternalRunContract.MAX_ASSET_PIXEL_EDGE;
import static com.proovy.domain.run.brain.InternalRunContract.optionalRange;
import static com.proovy.domain.run.brain.InternalRunContract.require;
import static com.proovy.domain.run.brain.InternalRunContract.violation;

/**
 * Brain {@code _ScopedAsset}의 media 사실 검사. {@link ViewerContext}·{@link TurnAttachment}가 공유한다.
 * PDF는 page_count만, 이미지는 pixel 세 값만 가지고 pixel_count = width × height다.
 */
final class ScopedAssetFacts {

    private ScopedAssetFacts() {
    }

    static void check(String field, AssetMime mime, Long byteSize, Integer pageCount,
            Integer pixelWidth, Integer pixelHeight, Long pixelCount) {
        require(mime, field + ".mime");
        require(byteSize, field + ".byte_size");
        optionalRange(byteSize, 1, MAX_ASSET_BYTES, field + ".byte_size");
        optionalRange(pageCount, 1, MAX_ASSET_PAGES, field + ".page_count");
        optionalRange(pixelWidth, 1, MAX_ASSET_PIXEL_EDGE, field + ".pixel_width");
        optionalRange(pixelHeight, 1, MAX_ASSET_PIXEL_EDGE, field + ".pixel_height");
        optionalRange(pixelCount, 1, MAX_ASSET_PIXEL_COUNT, field + ".pixel_count");
        if (mime.isPdf()) {
            if (pageCount == null) {
                throw violation(field + ".page_count", "is required for a pdf");
            }
            if (pixelWidth != null || pixelHeight != null || pixelCount != null) {
                throw violation(field, "pdf carries no pixel dimensions");
            }
            return;
        }
        if (pageCount != null) {
            throw violation(field + ".page_count", "is not allowed for an image");
        }
        if (pixelWidth == null || pixelHeight == null || pixelCount == null) {
            throw violation(field, "image requires pixel dimensions");
        }
        if (pixelCount != (long) pixelWidth * pixelHeight) {
            throw violation(field + ".pixel_count", "must equal width * height");
        }
    }
}
