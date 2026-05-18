-- V32: Rename S3 column names to generic object key names for GCS compatibility
ALTER TABLE assets RENAME COLUMN s3_key TO object_key;
ALTER TABLE assets RENAME COLUMN thumbnail_s3_key TO thumbnail_object_key;