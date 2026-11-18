-- Asset 이미지 픽셀 사실 컬럼 추가 (2.18 사진 인수 / 2.19 최소 선행)
ALTER TABLE assets
    ADD COLUMN pixel_width                  INTEGER,
    ADD COLUMN pixel_height                 INTEGER,
    ADD COLUMN pixel_count                  BIGINT,
    ADD COLUMN staging_object_key           VARCHAR(500),
    ADD COLUMN staging_generation           BIGINT,
    ADD COLUMN pending_confirmed_object_key VARCHAR(500);

ALTER TABLE assets
    ADD CONSTRAINT ck_assets_pixel_dimensions CHECK (
        (pixel_width IS NULL) = (pixel_height IS NULL)
        AND (pixel_width IS NULL) = (pixel_count IS NULL)
        AND (
            pixel_width IS NULL
            OR (
                pixel_width >= 1 AND pixel_width <= 16384
                AND pixel_height >= 1 AND pixel_height <= 16384
                AND pixel_count = pixel_width::BIGINT * pixel_height
            )
        )
        AND NOT (page_count IS NOT NULL AND pixel_width IS NOT NULL)
        AND (
            (mime_type = 'application/pdf' AND pixel_width IS NULL)
            OR (mime_type <> 'application/pdf' AND page_count IS NULL)
        )
    );
