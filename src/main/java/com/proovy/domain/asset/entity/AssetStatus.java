package com.proovy.domain.asset.entity;

public enum AssetStatus {
    PENDING,    // Presigned URL 발급됨, 업로드 대기
    UPLOADED,   // S3 업로드 완료
    FAILED      // 업로드 실패 또는 만료
}
