package com.proovy.domain.asset.entity;

public enum AssetStatus {
    PENDING,    // 업로드 URL 발급됨, 업로드 대기 (staging)
    ACTIVE,     // 업로드 확정된 Note Asset
    FAILED,     // 업로드 실패 또는 만료
    DELETING,   // 삭제 확인 직후, 새 run scope·source availability에서 제외
    DELETED     // Brain cleanup·GCS 정리 완료
}
