package com.proovy.domain.run.contract;

/** 409 RUN4091의 result. 다른 오류 응답에는 result가 없다. */
public record ActiveRunConflict(String activeRunId) {
}
