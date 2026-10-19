package com.proovy.domain.run.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * run 상태 (docs/plan/05). 허용 전이는 DB trigger(ai_runs_guard_update)가 강제한다.
 * <pre>
 * PENDING   → RUNNING | CANCELED | FAILED
 * RUNNING   → RETRYING | CANCELING | COMPLETED | FAILED
 * RETRYING  → RUNNING | CANCELING | COMPLETED | FAILED
 * CANCELING → CANCELED
 * </pre>
 */
public enum AiRunStatus {
    PENDING,
    RUNNING,
    RETRYING,
    CANCELING,
    COMPLETED,
    FAILED,
    CANCELED;

    /** Note별 Active Run partial unique index(ux_ai_runs_note_active)가 포함하는 상태 */
    public static final Set<AiRunStatus> ACTIVE = EnumSet.of(PENDING, RUNNING, RETRYING, CANCELING);

    public boolean isActive() {
        return ACTIVE.contains(this);
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELED;
    }
}
