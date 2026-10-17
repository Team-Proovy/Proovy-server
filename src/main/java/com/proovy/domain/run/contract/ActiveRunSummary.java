package com.proovy.domain.run.contract;

/** 대화 조회의 진행 중 run. status는 PENDING|RUNNING|CANCELING만. */
public record ActiveRunSummary(String runId, PublicRunStatus status, long lastSeq) {
}
