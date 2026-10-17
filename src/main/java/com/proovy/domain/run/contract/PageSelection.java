package com.proovy.domain.run.contract;

/** page-selection-v1 정규화 좌표. left&lt;right, top&lt;bottom, 모두 [0,1]. */
public record PageSelection(Double left, Double top, Double right, Double bottom) {
}
