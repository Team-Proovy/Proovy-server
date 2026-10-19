package com.proovy.domain.run.contract;

import com.fasterxml.jackson.annotation.JsonValue;

public enum TurnAttachmentMime {
    PDF("application/pdf"),
    JPEG("image/jpeg"),
    PNG("image/png"),
    WEBP("image/webp");

    private final String wireValue;

    TurnAttachmentMime(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
