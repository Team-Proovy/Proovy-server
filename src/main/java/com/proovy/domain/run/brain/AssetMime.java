package com.proovy.domain.run.brain;

import com.fasterxml.jackson.annotation.JsonValue;
import com.proovy.domain.run.entity.WireValue;

/** internal-run-v1 asset MIME allowlist. */
public enum AssetMime implements WireValue {
    PDF("application/pdf"),
    JPEG("image/jpeg"),
    PNG("image/png"),
    WEBP("image/webp");

    private final String wireValue;

    AssetMime(String wireValue) {
        this.wireValue = wireValue;
    }

    public boolean isPdf() {
        return this == PDF;
    }

    @Override
    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
