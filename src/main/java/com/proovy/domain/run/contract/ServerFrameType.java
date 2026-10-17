package com.proovy.domain.run.contract;

import java.util.Arrays;
import java.util.Optional;

/** Server가 직접 만드는 frame type과 payload 타입. */
public enum ServerFrameType {
    RUN_CREATED("run.created", RunCreatedPayload.class),
    RESPONSE_PAGE_STARTED("response.page.started", ResponsePageStartedPayload.class),
    RESPONSE_PAGE_COMPLETED("response.page.completed", ResponsePageCompletedPayload.class),
    RUN_CANCELING("run.canceling", RunCancelingPayload.class),
    RUN_CANCELED("run.canceled", RunCanceledPayload.class);

    private final String type;
    private final Class<?> payloadType;

    ServerFrameType(String type, Class<?> payloadType) {
        this.type = type;
        this.payloadType = payloadType;
    }

    public static Optional<ServerFrameType> of(String type) {
        return Arrays.stream(values()).filter(value -> value.type.equals(type)).findFirst();
    }

    public String type() {
        return type;
    }

    public Class<?> payloadType() {
        return payloadType;
    }
}
