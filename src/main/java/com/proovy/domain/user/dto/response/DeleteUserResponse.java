package com.proovy.domain.user.dto.response;

import lombok.Builder;

@Builder
public record DeleteUserResponse(
        String deletedAt
) {
    public static DeleteUserResponse of(String deletedAt) {
        return DeleteUserResponse.builder()
                .deletedAt(deletedAt)
                .build();
    }
}
