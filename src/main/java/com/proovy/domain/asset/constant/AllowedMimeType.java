package com.proovy.domain.asset.constant;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Getter
@RequiredArgsConstructor
public enum AllowedMimeType {
    PDF("application/pdf", "pdf"),
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
    WEBP("image/webp", "webp");

    private final String mimeType;
    private final String extension;

    private static final Set<String> ALLOWED_MIME_TYPES = Arrays.stream(values())
            .map(AllowedMimeType::getMimeType)
            .collect(Collectors.toSet());

    public static boolean isAllowed(String mimeType) {
        return ALLOWED_MIME_TYPES.contains(mimeType);
    }

    public static AllowedMimeType fromMimeType(String mimeType) {
        return Arrays.stream(values())
                .filter(type -> type.getMimeType().equals(mimeType))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported mime type: " + mimeType));
    }
}
