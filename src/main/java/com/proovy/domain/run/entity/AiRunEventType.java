package com.proovy.domain.run.entity;

/** 영속 browser event type (docs/plan/20). */
public enum AiRunEventType implements WireValue {
    RUN_CREATED("run.created", true),
    RUN_CANCELING("run.canceling", true),
    RUN_CANCELED("run.canceled", true),
    RUN_FAILED("run.failed", false),
    RUN_COMPLETED("run.completed", false),
    ACTIVITY_STARTED("activity.started", false),
    ACTIVITY_UPDATED("activity.updated", false),
    ACTIVITY_COMPLETED("activity.completed", false),
    ACTIVITY_FAILED("activity.failed", false),
    ACTIVITY_CANCELED("activity.canceled", false),
    RESPONSE_PAGE_STARTED("response.page.started", true),
    RESPONSE_PAGE_COMPLETED("response.page.completed", true);

    private final String wireValue;
    private final boolean serverOwned;

    AiRunEventType(String wireValue, boolean serverOwned) {
        this.wireValue = wireValue;
        this.serverOwned = serverOwned;
    }

    @Override
    public String wireValue() {
        return wireValue;
    }

    /** Server 소유 event는 Brain source_event_id·attempt를 갖지 않는다 (ck_ai_run_events_server_owned). */
    public boolean isServerOwned() {
        return serverOwned;
    }

    public boolean isActivity() {
        return wireValue.startsWith("activity.");
    }

    public static class Converter extends WireValue.Converter<AiRunEventType> {
        public Converter() {
            super(AiRunEventType.class);
        }
    }
}
