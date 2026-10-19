package com.proovy.domain.run.entity;

/** run이 생성 시 고정한 Note Asset 의존 종류 (삭제 fencing 대상 결정). */
public enum RunAssetDependencyKind {
    VIEWER_FOCUS,
    CORPUS_SNAPSHOT
}
